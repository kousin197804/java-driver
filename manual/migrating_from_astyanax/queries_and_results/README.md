# Migrate from Astyanax to Java Driver - Queries and Results
If you have been reading the article linked in Section 1 (From Thrift to CQL), 
you now know how to translate your previous _Astyanax_ keyspace operations into 
_CQL_ queries. With the _Java driver_ one needs to execute those queries by passing 
them through the session. The queries can either be simple strings or 
represented in the form of `Statement`s. The driver offers 4 kinds of statements, 
`SimpleStatement`, `Prepared/BoundStatement`, `BuiltStatement`, `BatchStatement`. 
All necessary information can be found here [DOC-LINK] about the natures of the 
different `Statement`s.

As explained earlier, results of a _CQL_ query will be in the form of _Rows_ from 
_Tables_, composed of fixed set of columns, each with a type and a name. The 
driver exposes the set of _Rows_ returned from a query as a ResultSet, thus 
containing _Rows_ on which `getXXX()` can be called. Here are simple examples of 
translation from _Astyanax_ to _Java driver_ in querying and retrieving
query results.

## Single column

_Astyanax :_

```java
ColumnFamily<String, String> CF_STANDARD1 = new ColumnFamily<String, String>("cf1", StringSerializer.get(), StringSerializer.get(). StringSerializer.get());

Column<String> result = keyspace.prepareQuery(CF_STANDARD1)
    .getKey("1")
    .getColumn("3")
    .execute().getResult();
String value = result.getStringValue();
```

_Java driver :_

```
Row row = session.execute("SELECT value FROM table1 WHERE key = '1' AND column1 = '3'").one();
String value = row.getString("value");
```

## All columns

_Astyanax :_ 

```java
ColumnList<String> columns;
int pagesize = 10;
RowQuery<String, String> query = keyspace
       .prepareQuery(CF_STANDARD1)
       .getKey("1")
       .autoPaginate(true)
       .withColumnRange(new RangeBuilder().setLimit(pagesize).build());

while (!(columns = query.execute().getResult()).isEmpty()) {
   for (Column<String> c : columns) {
       String value = c.getStringValue();
   }
}
```

_Java driver :_
```java
ResultSet rs = session.execute("SELECT value FROM table1 WHERE key = '1'");
for (Row row : rs) {
   String value = row.getString("value");
}
```

## Column range

_Astyanax :_

```java
ColumnList<String> result;
result = keyspace.prepareQuery(CF_STANDARD1)
       .getKey("1")
       .withColumnRange(new RangeBuilder().setStart("3").setEnd("5").setMaxSize(100).build())
       .execute().getResult();

Iterator<Column<String>> it = result.iterator();
while (it.hasNext()) {
   Column<String> col = it.next();
   String value = col.getStringValue();
}
```

_Java driver :_

```java
ResultSet rs = session.execute("SELECT value FROM table1 WHERE key = '1'" +
       " AND column1 > '3'" +
       " AND column1 < '5'");
for (Row row : rs) {
   String value = row.getString("value");
}
```

## Async
The Java driver provides native support for asynchronous programming and is 
inherently built upon an asynchronous protocol, please see this page [DOC-LINK] 
for usage and best practices.

