/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import com.datastax.driver.core.exceptions.BusyConnectionException;
import com.datastax.driver.core.exceptions.ConnectionException;
import com.datastax.driver.core.exceptions.UnsupportedProtocolVersionException;
import com.datastax.driver.core.utils.MoreFutures;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.*;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.OneTimeTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

class HostConnectionPool implements Connection.Owner {

    private static final Logger logger = LoggerFactory.getLogger(HostConnectionPool.class);

    private enum Phase {INITIALIZING, READY, INIT_FAILED, CLOSING}

    final Host host;
    volatile HostDistance hostDistance;
    private final SessionManager manager;

    // To simplify concurrency, all operations that modify the pool's state will be confined to a specific Netty thread:
    private final EventExecutor executor;

    /* Confined state: these fields must only be mutated by code running on executor */
    @VisibleForTesting
    final List<Connection> connections;

    @VisibleForTesting
    final Set<Connection> trash;

    private final Queue<BorrowTask> pendingBorrowQueue;

    // The maximum value of {@link #totalInFlight} since the last call to {@link #cleanupIdleConnections(long)}
    private int maxTotalInFlight = 0;

    // The number of connections currently being opened. This is generally restricted to 1, except when we bring the
    // pool to its core size.
    private int opening;

    private final ScheduledFuture<?> cleanup;

    private Phase phase = Phase.INITIALIZING;
    /* end confined state */


    // Maintain these separately for metrics (volatile allows querying them outside of adminThread)
    private volatile int poolSize;
    private volatile int trashSize;
    private volatile int totalInFlight;

    protected final AtomicReference<CloseFuture> closeFuture = new AtomicReference<CloseFuture>();

    // When a request times out, we may never release its stream ID. So over time, a given connection
    // may get less an less available streams. When the number of available ones go below the
    // following threshold, we just replace the connection by a new one.
    private final int minAllowedStreams;

    HostConnectionPool(Host host, HostDistance hostDistance, SessionManager manager) {
        assert hostDistance != HostDistance.IGNORED;
        this.host = host;
        this.hostDistance = hostDistance;
        this.manager = manager;

        this.executor = manager.connectionFactory().eventLoopGroup.next();

        this.connections = Lists.newArrayList();
        this.trash = Sets.newHashSet();
        this.pendingBorrowQueue = new ArrayDeque<BorrowTask>();

        this.minAllowedStreams = options().getMaxRequestsPerConnection(hostDistance) * 3 / 4;

        this.cleanup = executor.scheduleWithFixedDelay(new CleanupTask(), 10, 10, TimeUnit.SECONDS);
    }

    /**
     * @param reusedConnection an existing connection (from a reconnection attempt) that we want to
     *                         reuse as part of this pool. Might be null or already used by another
     *                         pool.
     */
    ListenableFuture<Void> initAsync(final Connection reusedConnection) {
        final SettableFuture<Void> initFuture = SettableFuture.create();
        if (executor.inEventLoop()) {
            confinedInitAsync(reusedConnection, initFuture);
        } else {
            executor.execute(new OneTimeTask() {
                @Override
                public void run() {
                    confinedInitAsync(reusedConnection, initFuture);
                }
            });
        }
        return initFuture;
    }

    private void confinedInitAsync(Connection reusedConnection, final SettableFuture<Void> initFuture) {
        assert executor.inEventLoop();

        // Create initial core connections
        final int coreSize = options().getCoreConnectionsPerHost(hostDistance);
        final List<Connection> connections = Lists.newArrayListWithCapacity(coreSize);
        final List<ListenableFuture<Void>> connectionFutures = Lists.newArrayListWithCapacity(coreSize);

        int toCreate = coreSize;

        if (reusedConnection != null && reusedConnection.setOwner(this)) {
            toCreate -= 1;
            connections.add(reusedConnection);
            connectionFutures.add(MoreFutures.VOID_SUCCESS);
        }

        List<Connection> newConnections = manager.connectionFactory().newConnections(this, toCreate);
        connections.addAll(newConnections);
        for (Connection connection : newConnections) {
            ListenableFuture<Void> connectionFuture = connection.initAsync();
            connectionFutures.add(handleErrors(connectionFuture));
        }

        ListenableFuture<List<Void>> allConnectionsFuture = Futures.allAsList(connectionFutures);

        Futures.addCallback(allConnectionsFuture, new FutureCallback<List<Void>>() {
            @Override
            public void onSuccess(List<Void> l) {
                // Some of the connections might have failed, keep only the successful ones
                ListIterator<Connection> it = connections.listIterator();
                while (it.hasNext()) {
                    if (it.next().isClosed())
                        it.remove();
                }

                HostConnectionPool.this.connections.addAll(connections);
                poolSize = connections.size();

                if (isClosed()) {
                    initFuture.setException(new ConnectionException(host.getSocketAddress(), "Pool was closed during initialization"));
                    // we're not sure if closeAsync() saw the connections, so ensure they get closed
                    forceClose(connections);
                } else {
                    logger.debug("Created connection pool to host {} ({} connections needed, {} successfully opened)",
                            host, coreSize, poolSize);
                    phase = Phase.READY;
                    initFuture.set(null);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                phase = Phase.INIT_FAILED;
                forceClose(connections);
                initFuture.setException(t);
            }
        }, executor);
    }

    private ListenableFuture<Void> handleErrors(ListenableFuture<Void> connectionInitFuture) {
        return Futures.withFallback(connectionInitFuture, new FutureFallback<Void>() {
            @Override
            public ListenableFuture<Void> create(Throwable t) throws Exception {
                // Propagate these exceptions because they mean no connection will ever succeed. They will be handled
                // accordingly in SessionManager#maybeAddPool.
                Throwables.propagateIfInstanceOf(t, ClusterNameMismatchException.class);
                Throwables.propagateIfInstanceOf(t, UnsupportedProtocolVersionException.class);

                // We don't want to swallow Errors either as they probably indicate a more serious issue (OOME...)
                Throwables.propagateIfInstanceOf(t, Error.class);

                // Otherwise, return success. The pool will simply ignore this connection when it sees that it's been closed.
                return MoreFutures.VOID_SUCCESS;
            }
        });
    }

    // Clean up if we got a fatal error at construction time but still created part of the core connections
    private void forceClose(List<Connection> connections) {
        for (Connection connection : connections) {
            connection.closeAsync().force();
        }
    }

    private PoolingOptions options() {
        return manager.configuration().getPoolingOptions();
    }

    ListenableFuture<Connection> borrowConnection(final long timeout, final TimeUnit unit) {
        final SettableFuture<Connection> borrowFuture = SettableFuture.create();
        if (executor.inEventLoop()) {
            confinedBorrow(timeout, unit, borrowFuture);
        } else {
            executor.execute(new OneTimeTask() {
                @Override
                public void run() {
                    confinedBorrow(timeout, unit, borrowFuture);
                }
            });
        }
        return borrowFuture;
    }

    private void confinedBorrow(long timeout, TimeUnit unit, SettableFuture<Connection> borrowFuture) {
        assert executor.inEventLoop();

        if (phase != Phase.READY) {
            borrowFuture.setException(new ConnectionException(host.getSocketAddress(), "Pool is " + phase));
            return;
        }

        if (connections.isEmpty()) {
            if (options().getCoreConnectionsPerHost(hostDistance) == 0)
                maybeSpawnNewConnection();
            else
                confinedEnsureCoreConnections();
            enqueue(borrowFuture, timeout, unit);
            return;
        }

        int minInFlight = Integer.MAX_VALUE;
        Connection leastBusy = null;
        for (Connection connection : connections) {
            int inFlight = connection.inFlight;
            if (inFlight < minInFlight) {
                minInFlight = inFlight;
                leastBusy = connection;
            }
        }

        if (leastBusy == null || minInFlight >= Math.min(leastBusy.maxAvailableStreams(), options().getMaxRequestsPerConnection(hostDistance))) {
            enqueue(borrowFuture, timeout, unit);
        } else {
            onBorrowed(borrowFuture, leastBusy);
        }
    }

    private void enqueue(SettableFuture<Connection> borrowFuture, long timeout, TimeUnit unit) {
        assert executor.inEventLoop();
        if (timeout == 0) {
            borrowFuture.setException(new TimeoutException("No connection immediately available and pool timeout is 0"));
        } else {
            pendingBorrowQueue.offer(
                    new BorrowTask(borrowFuture, TimeUnit.NANOSECONDS.convert(timeout, unit)));
        }
    }

    // Finalize work once we've selected a connection to return to a borrow request
    private void onBorrowed(final SettableFuture<Connection> borrowFuture, final Connection connection) {
        assert executor.inEventLoop();

        connection.inFlight += 1;
        totalInFlight += 1;

        maxTotalInFlight = Math.max(maxTotalInFlight, totalInFlight);

        // Maintenance: check if the pool needs to grow given its current workload
        int connectionCount = this.poolSize + this.opening;
        if (connectionCount < options().getCoreConnectionsPerHost(hostDistance)) {
            maybeSpawnNewConnection();
        } else if (connectionCount < options().getMaxConnectionsPerHost(hostDistance)) {
            // Add a connection if we fill the first n-1 connections and almost fill the last one
            int currentCapacity = (connectionCount - 1) * options().getMaxRequestsPerConnection(hostDistance)
                    + options().getNewConnectionThreshold(hostDistance);
            if (totalInFlight > currentCapacity)
                maybeSpawnNewConnection();
        }

        // Set keyspace if needed, then return
        try {
            ListenableFuture<Void> setKeyspaceFuture = connection.setKeyspaceAsync(manager.poolsState.keyspace);
            Futures.addCallback(setKeyspaceFuture,
                    new FutureCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            borrowFuture.set(connection);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            borrowFuture.setException(t);
                        }
                    });
        } catch (ConnectionException e) {
            borrowFuture.setException(e);
        } catch (BusyConnectionException e) {
            borrowFuture.setException(e);
        }
    }

    // Open a new connection unless we're already opening one
    private void maybeSpawnNewConnection() {
        assert executor.inEventLoop();

        if (!host.convictionPolicy.canReconnectNow()
                || opening > 0
                || poolSize >= options().getMaxConnectionsPerHost(hostDistance)) {
            return;
        }

        opening = 1;
        spawnNewConnection();
    }

    private void spawnNewConnection() {
        assert executor.inEventLoop();

        Connection resurrected = tryResurrectFromTrash();
        if (resurrected != null) {
            // Successfully resurrected from trash, it's already initialized so we can dequeue immediately
            connections.add(resurrected);
            poolSize = connections.size();
            opening -= 1;
            dequeuePendingBorrows(resurrected);
        } else if (!host.convictionPolicy.canReconnectNow()) {
            opening -= 1;
        } else {
            logger.debug("Creating new connection on busy pool to {}", host);
            final Connection connection = manager.connectionFactory().newConnection(this);
            ListenableFuture<Void> initFuture = connection.initAsync();
            Futures.addCallback(initFuture, new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    connections.add(connection);
                    poolSize = connections.size();
                    dequeuePendingBorrows(connection);
                    opening -= 1;
                }

                @Override
                public void onFailure(Throwable t) {
                    logger.debug("Error while spawning new connection, this will be tried again", t);
                    opening -= 1;
                }
            }, executor);
        }
    }

    private Connection tryResurrectFromTrash() {
        assert executor.inEventLoop();

        long highestMaxIdleTime = System.currentTimeMillis();
        Connection chosen = null;

        for (Connection connection : trash)
            if (connection.maxIdleTime > highestMaxIdleTime && connection.maxAvailableStreams() > minAllowedStreams) {
                chosen = connection;
                highestMaxIdleTime = connection.maxIdleTime;
            }

        if (chosen == null)
            return null;

        logger.trace("Resurrecting {}", chosen);
        trash.remove(chosen);
        trashSize = trash.size();
        return chosen;
    }

    private void dequeuePendingBorrows(Connection connection) {
        assert executor.inEventLoop();

        while (connection.inFlight < connection.maxAvailableStreams()) {
            BorrowTask borrowTask = pendingBorrowQueue.poll();
            if (borrowTask == null)
                break;

            borrowTask.timeoutFuture.cancel(false);
            onBorrowed(borrowTask.future, connection);
        }
    }

    public void returnConnection(final Connection connection) {
        if (executor.inEventLoop()) {
            confinedReturnConnection(connection);
        } else {
            executor.execute(new OneTimeTask() {
                @Override
                public void run() {
                    confinedReturnConnection(connection);
                }
            });
        }
    }

    private void confinedReturnConnection(Connection connection) {
        assert executor.inEventLoop();

        connection.inFlight -= 1;
        totalInFlight -= 1;

        if (isClosed()) {
            connection.closeAsync();
            return;
        }

        if (connection.isDefunct()) {
            // As part of making it defunct, we have already replaced it or closed the pool.
            return;
        }

        if (connection.maxAvailableStreams() < minAllowedStreams) {
            replaceConnection(connection);
        } else {
            dequeuePendingBorrows(connection);
        }
    }

    private void replaceConnection(Connection connection) {
        assert executor.inEventLoop();

        maybeSpawnNewConnection();
        connection.maxIdleTime = Long.MIN_VALUE;
        doTrashConnection(connection);
    }

    private void doTrashConnection(Connection connection) {
        assert executor.inEventLoop();

        connections.remove(connection);
        trash.add(connection);
        poolSize = connections.size();
        trashSize = trash.size();
    }

    @Override
    public void onConnectionDefunct(final Connection connection) {
        if (executor.inEventLoop()) {
            // Don't try to replace the connection now. Connection.defunct already signaled the failure,
            // and either the host will be marked DOWN (which destroys all pools), or we want to prevent
            // new connections for some time
            connections.remove(connection);
            poolSize = connections.size();
        } else {
            executor.execute(new OneTimeTask() {
                @Override
                public void run() {
                    connections.remove(connection);
                    poolSize = connections.size();
                }
            });
        }
    }

    private class BorrowTask {
        private final SettableFuture<Connection> future;
        private final long expireNanoTime;
        private final ScheduledFuture<?> timeoutFuture;

        BorrowTask(SettableFuture<Connection> future, long timeout) {
            this.future = future;
            this.expireNanoTime = System.nanoTime() + timeout;
            this.timeoutFuture = executor.schedule(timeoutTask, timeout, TimeUnit.NANOSECONDS);
        }

        private Runnable timeoutTask = new Runnable() {
            @Override
            public void run() {
                assert executor.inEventLoop();
                long now = System.nanoTime();
                while (true) {
                    BorrowTask task = pendingBorrowQueue.peek();
                    if (task == null || task.expireNanoTime > now) {
                        break;
                    }
                    pendingBorrowQueue.remove();
                    task.timeoutFuture.cancel(false);
                    task.future.setException(new TimeoutException("Could not acquire connection within the given timeout"));
                }
            }
        };
    }

    private class CleanupTask implements Runnable {
        @Override
        public void run() {
            assert executor.inEventLoop();

            if (isClosed())
                return;

            shrinkIfBelowCapacity();
            cleanupTrash(System.currentTimeMillis());
        }
    }

    /**
     * If we have more active connections than needed, trash some of them
     */
    private void shrinkIfBelowCapacity() {
        int currentLoad = maxTotalInFlight;
        maxTotalInFlight = totalInFlight;

        int maxRequestsPerConnection = options().getMaxRequestsPerConnection(hostDistance);
        int needed = currentLoad / maxRequestsPerConnection + 1;
        if (currentLoad % maxRequestsPerConnection > options().getNewConnectionThreshold(hostDistance))
            needed += 1;
        needed = Math.max(needed, options().getCoreConnectionsPerHost(hostDistance));
        int actual = poolSize;
        int toTrash = Math.max(0, actual - needed);

        logger.trace("Current inFlight = {}, {} connections needed, {} connections available, trashing {}",
                currentLoad, needed, actual, toTrash);

        if (toTrash <= 0)
            return;

        for (Connection connection : connections)
            if (trashConnection(connection)) {
                toTrash -= 1;
                if (toTrash == 0)
                    return;
            }
    }

    /**
     * Close connections that have been sitting in the trash for too long
     */
    private void cleanupTrash(long now) {
        for (Connection connection : trash) {
            if (connection.maxIdleTime < now) {
                logger.trace("Cleaning up {}", connection);
                trash.remove(connection);
                connection.closeAsync();
            }
        }
        trashSize = trash.size();
    }

    private boolean trashConnection(Connection connection) {
        assert executor.inEventLoop();

        if (poolSize <= options().getCoreConnectionsPerHost(hostDistance))
            return false;

        connection.maxIdleTime = System.currentTimeMillis() + options().getIdleTimeoutSeconds() * 1000;
        doTrashConnection(connection);
        return true;
    }

    CloseFuture closeAsync() {
        CloseFuture future = closeFuture.get();
        if (future != null)
            return future;

        final CloseFuture.Forwarding myFuture = new CloseFuture.Forwarding();
        if (closeFuture.compareAndSet(null, myFuture)) {
            if (executor.inEventLoop()) {
                confinedClose(myFuture);
            } else {
                executor.execute(new OneTimeTask() {
                    @Override
                    public void run() {
                        confinedClose(myFuture);
                    }
                });
            }
            return myFuture;
        } else {
            return closeFuture.get();
        }
    }

    private void confinedClose(CloseFuture.Forwarding forwardingFuture) {
        assert executor.inEventLoop();

        phase = Phase.CLOSING;

        cleanup.cancel(false);

        // Notify all clients that were waiting for a connection
        BorrowTask borrowTask;
        while ((borrowTask = pendingBorrowQueue.poll()) != null) {
            borrowTask.timeoutFuture.cancel(false);
            borrowTask.future.setException(new ConnectionException(host.getSocketAddress(), "Pool is " + phase));
        }

        forwardingFuture.setDependencies(discardAvailableConnections());
    }

    private List<CloseFuture> discardAvailableConnections() {
        assert executor.inEventLoop();

        // Note: if this gets called before initialization has completed, both connections and trash will be empty,
        // so this will return an empty list

        List<CloseFuture> futures = new ArrayList<CloseFuture>(poolSize + trashSize);

        for (final Connection connection : connections)
            futures.add(connection.closeAsync());

        // Some connections in the trash might still be open if they hadn't reached their idle timeout
        for (Connection connection : trash)
            futures.add(connection.closeAsync());

        return futures;
    }

    final boolean isClosed() {
        return closeFuture.get() != null;
    }

    // This creates connections if we have less than core connections (if we have more than core, connection will just
    // get trashed when we can).
    void ensureCoreConnections() {
        if (isClosed())
            return;

        if (!host.convictionPolicy.canReconnectNow())
            return;

        if (executor.inEventLoop()) {
            confinedEnsureCoreConnections();
        } else {
            executor.execute(new OneTimeTask() {
                @Override
                public void run() {
                    confinedEnsureCoreConnections();
                }
            });
        }
    }

    private void confinedEnsureCoreConnections() {
        assert executor.inEventLoop();

        int needed = options().getCoreConnectionsPerHost(hostDistance) - poolSize - opening;
        if (needed > 0) {
            opening += needed;
            for (int i = 0; i < needed; i++) {
                spawnNewConnection();
            }
        }
    }

    public int opened() {
        return poolSize;
    }

    int trashed() {
        return trashSize;
    }

    int totalInFlight() {
        return totalInFlight;
    }

    static class PoolState {
        volatile String keyspace;

        void setKeyspace(String keyspace) {
            this.keyspace = keyspace;
        }
    }
}
