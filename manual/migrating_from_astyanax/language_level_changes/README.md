# Language level change - from Thrift to CQL.
First thing to know, the data model is going to change when using _CQL_ (Cassandra Query Language). 
_CQL_ is providing an abstraction of the low-level data stored in _Cassandra_, in
opposition to _Thrift_ that aims to expose the low-level data structure directly.
[But note that this tends to change with Cassandra 3’s new storage engine.][STORAGEENGINE30-POST-LINK]

_Thrift_ exposes _Keyspaces_, and these _Keyspaces_ contain _Column Families_. A
_ColumnFamily_ contains _Rows_ in which each _Row_ has a list of an arbitrary number
of column-values. With _CQL_, the becomes is now *tabular*, _ColumnFamily_ gets viewed
as a _Table_, the _Table Rows_ get a fixed and finite number of named columns.
_Thrift_’s columns inside the _Rows_ get distributed in a tabular way through the
_Table Rows_. See the following figure :



Some of the columns of a _CQL Table_ have a special role that is specifically
related to the _Cassandra_ architecture. Indeed, the _Row Key_ of the _Thrift Row_,
becomes the _Partition Key_ in the _CQL Table_, and can be composed of 1 or multiple
_CQL columns_ (the key column in Figure 1). The _“Column”_ part of the Column-value
component in a _Thrift Row_, becomes the _Clustering ColumnKey_ in _CQL_, and can
also be composed of multiple columns (in the figure, column1 is the only column 
composing the _Clustering ColumnKey_).

Here is the basic architectural concept of _CQL_, this post will not dive more
into describing the _CQL_ language, however a detailed explanation and _CQL_
examples can be found in this article :  http://www.planetcassandra.org/making-the-change-from-thrift-to-cql/. 
Understanding the _CQL_ abstraction plays a key role in developing performing
and scaling applications.
