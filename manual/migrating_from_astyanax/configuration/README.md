# Migrate from Astyanax to Java Driver - Configuration

## How Configuring the Java driver works

The two basic components in the Java driver are the Cluster and the Session. 
The Cluster is the object you will create first and on which you will apply all 
your global configuration options. Then, connecting to the Cluster creates a 
Session. Queries are executed through the Session.

The Cluster object then is to be viewed as the equivalent of the AstyanaxContext 
object. When ‘starting’ an AstyanaxContext object you typically get a Keyspace 
object, the Keyspace object is the equivalent of the Java driver’s Session.

Configuring a Cluster works with the Builder pattern, and the Builder takes all 
the configurations into account before building the Cluster.

Following are some examples of the most important configurations that were 
possible with Astyanax and how to translate them into DS Java driver 
configurations.

## Connection pools

Configuration of connection pools in Astyanax are made through the 
ConnectionPoolConfigurationImpl. This object gathers important configurations 
that the Java driver has categorized in multiple Option and Policy kinds.

### Connections pools internals
Everything concerning the internal pools of connections to the Cassandra nodes 
will be gathered in the Java driver in the PoolingOptions :

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

Timeouts concerning requests, or connections will be part of the SocketOptions.

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
Both Astyanax and the Java driver connect to multiple nodes of the Cassandra 
cluster. Distributing requests through all the nodes plays an important role in 
the good behaviour of the Cluster and for performance. With Astyanax, requests 
(or “operations”) can be sent directly to replicas that have a copy of the data 
targeted by the “Row key” specified in the operation, since the API is low-level, 
it forces the user to provide Row keys, known as the TokenAware connection pool 
type. This setting is also present in the Java driver, however the configuration 
is different and provides more options to tweak.

Load balancing in the Java driver is a Policy, it is a class that will be 
plugged in the Java driver’s code and the Driver will call its methods when it 
needs to. The Java driver comes with a preset of specific load balancing policies. 
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

By default the Java driver will instantiate the exact Load balancing policy 
shown above, with the LocalDC being the DC of the first host the driver connects 
to. So to get the same behaviour than the TokenAware pool type of Astyanax, 
users shouldn’t need to specify a load balancing policy since the default one 
should cover it.

Note that since CQL is an abstraction of the Cassandra’s architecture, a simple 
query needs to have the Row key specified explicitly on a Statement in order 
to benefit from the TokenAware routing (and the Row key in the Java driver is 
referenced as Routing Key), unlike the Astyanax driver. Unless using prepared 
statements. Please see [DOC-LINK] for specific information.

Load balancing policies can easily be implemented by the user, and supplied to 
the Driver for a specific use case.

## Consistency levels
Consistency levels can be set per-statement, or globally through the QueryOptions.

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

Since the Java driver only executes CQL statements, which can be either reads 
or writes in Cassandra, it is not possible to globally configure the 
Consistency Level for only reads or only writes. To do so, since the Consistency 
Level can be set per-statement, you can either set it on every statement, or use 
PreparedStatements (if queries are to be repeated with different values): in 
this case, set the CL on the PreparedStatement, and the BoundStatements will 
inherit the CL from the Prepared statements they were prepared from. More 
informations about how Statements work in the Java driver are detailed later 
in the “Queries and Result” section.


## Authentication

Authentication settings are managed by the AuthProvider class in the Java driver. 
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

The class AuthProvider can be easily implemented to suit the user’s needs, 
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

The Java driver by default connects to port 9042, hence you can supply only
host names with the addContactPoints(String...) method. Note that the contact
points are only the entry points to the Cluster for the Automatic discovery
phase, the driver will connect to all hosts of the cluster, in the DC of the
first host in the contact points.

## Building the Cluster
With all options previously presented, one may configure and create the
Cluster object this way :

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
Pools management should also help [DOC-LINK].

A lot more options are available in the different XxxxOptions classes, policies are 
also highly customizable since the base drivers implementations can easily be 
extended and implement users specific actions.
