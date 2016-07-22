# Language level change - from Thrift to CQL.
First thing to know, the data model is going to change when using CQL. CQL is 
providing an abstraction of the low-level data stored in Cassandra, in opposition 
to Thrift that aims to expose the low-level data structure directly. 
[Even though this tends to change with Cassandra 3’s new storage engine.]

Thrift exposes Keyspaces, and these Keyspaces contain Column Families. A 
ColumnFamily contains Rows in which each Row has a list of an arbitrary number 
of column-values. With CQL,  the data is now tabular, ColumnFamily gets viewed 
as a Table, the Table Rows get a fixed and finite number of named columns. 
Thrift’s columns inside the Rows get distributed in a tabular way through the 
Table Rows. See the following figure : 



Some of the columns of a CQL Table have a special role that is specifically 
related to the Cassandra architecture. Indeed, the Row Key of the Thrift Row, 
becomes the Partition Key in the CQL Table, and can be composed of 1 or multiple 
CQL columns (the key column in Figure 1). The “Column” part of the Column-value 
component in a Thrift Row, becomes the Clustering ColumnKey in CQL, and can 
also be composed of multiple columns (in the figure, column1 is the only column 
composing the Clustering ColumnKey). 

Here is the basic architectural concept of CQL, this post will not dive more 
into describing the CQL language, however a detailed explanation and CQL 
examples can be found in this article :  http://www.planetcassandra.org/making-the-change-from-thrift-to-cql/. 
Understanding the CQL abstraction plays a key role in developing performing 
and scaling applications.
