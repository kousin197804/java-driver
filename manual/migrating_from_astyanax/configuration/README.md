# Migrate from Astyanax to Java Driver - Configuration

## How Configuring the Java driver works

The two basic components in the Java driver are the `Cluster` and the `Session`.
The `Cluster` is the object to create first and on which you will apply all
your global configuration options. Connecting to the `Cluster` creates a
`Session`. Queries are executed through the `Session`.

The `Cluster` object then is to be viewed as the equivalent of the `AstyanaxContext`
object. When ‘starting’ an `AstyanaxContext` object you typically get a `Keyspace`
object, the `Keyspace` object is the equivalent of the Java driver’s `Session`.

Configuring a `Cluster` works with the _Builder_ pattern, and the `Builder` takes all
the configurations into account before building the `Cluster`.

Following are some examples of the most important configurations that were 
possible with _Astyanax_ and how to translate them into _DataStax Java driver_
configurations.

## Connection pools

Configuration of connection pools in _Astyanax_ are made through the
`ConnectionPoolConfigurationImpl`. This object gathers important configurations
that the Java driver has categorized in multiple _Option_ and _Policy_ kinds.

### Connections pools internals
Everything concerning the internal pools of connections to the _Cassandra nodes_
will be gathered in the Java driver in the `PoolingOptions` :

_Astyanax :_

```java
ConnectionPoolConfigurationImpl cpool =
       new ConnectionPoolConfigurationImpl("myConnectionPool")
               .setMaxOperationsPerConnection(1024)
               .setInitConnsPerHost(2)
               .setMaxConnsPerHost(3)
```

_Java driver :_

```java
PoolingOptions poolingOptions =
       new PoolingOptions()
           .setMaxRequestsPerConnection(HostDistance.LOCAL, 1024)
           .setCoreConnectionsPerHost(HostDistance.LOCAL, 2)
           .setMaxConnectionsPerHost(HostDistance.LOCAL, 3);
```

### Timeouts

Timeouts concerning requests, or connections will be part of the `SocketOptions`.

_Astyanax :_

```java
ConnectionPoolConfigurationImpl cpool =
       new ConnectionPoolConfigurationImpl("myConnectionPool")
               .setSocketTimeout(3000)
               .setConnectTimeout(3000)
```

_Java Driver :_

```java
SocketOptions so =
       new SocketOptions()
           .setReadTimeoutMillis(3000)
           .setConnectTimeoutMillis(3000);
```

## Load Balancing
Both _Astyanax_ and the _Java driver_ connect to multiple nodes of the _Cassandra_
cluster. Distributing requests through all the nodes plays an important role in 
the good operation of the `Cluster` and for best performances. With _Astyanax_, 
requests (or “operations”) can be sent directly to replicas that have a copy of 
the data targeted by the _“Row key”_ specified in the operation, since the API is 
low-level, it forces the user to provide _Row keys_, known as the `TokenAware` 
connection pool type. This setting is also present in the _Java driver_, however 
the configuration is different and provides more options to tweak.

Load balancing in the Java driver is a _Policy_, it is a class that will be 
plugged in the _Java driver_’s code and the Driver will call its methods when it 
needs to. The _Java driver_ comes with a preset of specific load balancing policies. 
Here’s an equivalent code :

_Astyanax :_

```java
final ConnectionPoolType poolType = ConnectionPoolType.TOKEN_AWARE;
final NodeDiscoveryType discType = NodeDiscoveryType.RING_DESCRIBE;
ConnectionPoolConfigurationImpl cpool =
       new ConnectionPoolConfigurationImpl("myConnectionPool")
               .setLocalDatacenter("myDC")
AstyanaxConfigurationImpl aconf =
       new AstyanaxConfigurationImpl()
               .setConnectionPoolType(poolType)
               .setDiscoveryType(discType)
```

_Java driver :_

```java
LoadBalancingPolicy lbp = new TokenAwarePolicy(
       DCAwareRoundRobinPolicy.builder()
       .withLocalDc("myDC")
       .build()
);
```

*By default* the _Java driver_ will instantiate the exact Load balancing policy 
shown above, with the `LocalDC` being the DC of the first host the driver connects 
to. So to get the same behaviour than the _TokenAware_ pool type of _Astyanax_, 
users shouldn’t need to specify a load balancing policy since the default one 
should cover it.

Important : Note that since _CQL_ is an abstraction of the Cassandra’s architecture, a simple 
query needs to have the _Row key_ specified explicitly on a `Statement` in order 
to benefit from the _TokenAware_ routing (the _Row key_ in the _Java driver_ is 
referenced as _Routing Key_), unlike the _Astyanax_ driver. 
Some differences occur related to the different kinds of `Statements` the _Java
driver_ provides. Please see [DOC-LINK] for specific information.

Custom load balancing policies can easily be implemented by users, and supplied to 
the _Java driver_ for specific use cases. All information necessary if available
in the Load balaning policies docs. [DOC-LINK]

## Consistency levels
Consistency levels can be set per-statement, or globally through the `QueryOptions`.

_Astyanax :_

```java
AstyanaxConfigurationImpl aconf =
       new AstyanaxConfigurationImpl()
               .setDefaultReadConsistencyLevel(ConsistencyLevel.CL_ALL)
               .setDefaultWriteConsistencyLevel(ConsistencyLevel.CL_ALL)
```

_Java driver :_

```java
QueryOptions qo = new QueryOptions().setConsistencyLevel(ConsistencyLevel.ALL);
```

Since the _Java driver_ only executes _CQL_ statements, which can be either reads
or writes in _Cassandra_, it is not possible to globally configure the
Consistency Level for only reads or only writes. To do so, since the Consistency 
Level can be set per-statement, you can either set it on every statement, or use 
`PreparedStatements` (if queries are to be repeated with different values): in
this case, set the CL on the `PreparedStatement`, and the `BoundStatements` will
inherit the CL from the prepared statements they were prepared from. More
informations about how `Statement`s work in the _Java driver_ are detailed
in the “Queries and Result” section [DOC-LINK].


## Authentication

Authentication settings are managed by the `AuthProvider` class in the _Java driver_.
It can be highly customizable, but also comes with default simple implementations :

_Astyanax :_

```java
AuthenticationCredentials authCreds = new SimpleAuthenticationCredentials("username", "password");
ConnectionPoolConfigurationImpl cpool =
       new ConnectionPoolConfigurationImpl("myConnectionPool")
               .setAuthenticationCredentials(authCreds)
```

_Java driver :_

```java
AuthProvider authProvider = new PlainTextAuthProvider("username", "password");
```

The class `AuthProvider` can be easily implemented to suit the user’s needs,
documentation about the classes needed is available there [DOC-LINK].

## Hosts and ports

Setting the “seeds” or first hosts to connect to can be done directly on the 
Cluster configuration Builder :

_Astyanax :_

```java
ConnectionPoolConfigurationImpl cpool =
       new ConnectionPoolConfigurationImpl("myConnectionPool")
               .setSeeds("127.0.0.1")
               .setPort(9160)
```

_Java driver :_

```java
Cluster cluster = Cluster.builder()
       .addContactPoint("127.0.0.1")
       .withPort(9042)
```

The _Java driver_ by default connects to port _9042_, hence you can supply only
host names with the `addContactPoints(String...)` method. Note that the contact
points are only the entry points to the `Cluster` for the _Automatic discovery
phase_, the driver will connect to all hosts of the cluster, in the DC of the
first host in the contact points.

## Building the Cluster
With all options previously presented, one may configure and create the
`Cluster` object this way :

_Java driver :_

```java
Cluster cluster = Cluster.builder()
       .addContactPoint("127.0.0.1")
       .withAuthProvider(authProvider)
       .withLoadBalancingPolicy(lbp)
       .withSocketOptions(so)
       .withPoolingOptions(poolingOptions)
       .withQueryOptions(qo)
       .build();
Session session = cluster.connect();
```

## Best Practices

A few best practices are summed up there : [ALEXP-POST-LINK]

Concerning connection pools, you may need to know that the Java driver’s 
default settings should allow most of the users to get the best out of the 
driver in terms of throughput, they have been thoroughly tested and tweaked to 
accommodate the users’ needs. If one still wishes to change those, first 
Monitoring the pools first is advised [DOC-LINK], then a deep dive in the 
Pools management should provide enough insight [DOC-LINK].

A lot more options are available in the different `XxxxOption`s classes, policies are
also highly customizable since the base drivers implementations can easily be 
extended and implement users specific actions.
