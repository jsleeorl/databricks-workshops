// Databricks notebook source
// MAGIC %md
// MAGIC # What's in this exercise
// MAGIC Basics of how to work with CosmosDB from Databricks <B>in batch</B>.<BR>
// MAGIC Section 03: Create operation (Crud)<BR>
// MAGIC   
// MAGIC **TTL related roadmap**:<br>
// MAGIC Currently, TTL is configurable at a table level and per entire row level<br>
// MAGIC On the roadmap: TTL for column<BR>
// MAGIC 
// MAGIC **Reference:**<br> 
// MAGIC 
// MAGIC   
// MAGIC **TODO:**<br> 
// MAGIC -Get ignoreNulls working<br>
// MAGIC -Figure out how to set consistency via session/CQL<br>
// MAGIC -Add links to reference section

// COMMAND ----------

// MAGIC %md
// MAGIC ## 3.0. Create operation (*C*RUD)

// COMMAND ----------

// MAGIC %run ../00-HowTo/2-CassandraUtils

// COMMAND ----------

import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

import spark.implicits._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Column
import org.apache.spark.sql.types.{StructType, StructField, StringType, IntegerType,LongType,FloatType,DoubleType, TimestampType}
import org.apache.spark.sql.cassandra._

//datastax Spark connector
import com.datastax.spark.connector._
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.driver.core.{ConsistencyLevel, DataType}
import com.datastax.spark.connector.writer.WriteConf

//CosmosDB library for multiple retry
import com.microsoft.azure.cosmosdb.cassandra

// Specify connection factory for Cassandra
spark.conf.set("spark.cassandra.connection.factory", "com.microsoft.azure.cosmosdb.cassandra.CosmosDbConnectionFactory")

// Parallelism and throughput configs
spark.conf.set("spark.cassandra.output.batch.size.rows", "1")
spark.conf.set("spark.cassandra.connection.connections_per_executor_max", "10")
spark.conf.set("spark.cassandra.output.concurrent.writes", "100")
spark.conf.set("spark.cassandra.concurrent.reads", "512")
spark.conf.set("spark.cassandra.output.batch.grouping.buffer.size", "1000")
spark.conf.set("spark.cassandra.connection.keep_alive_ms", "60000000") //Increase this number as needed
spark.conf.set("spark.cassandra.output.ignoreNulls","true")
spark.conf.set("spark.cassandra.output.consistency.level","ALL")//Write consistency = Strong
spark.conf.set("spark.cassandra.input.consistency.level","ALL")//Read consistency = Strong

// COMMAND ----------

// MAGIC %md
// MAGIC #### 3.0.1. Dataframe API
// MAGIC Covers per record TTL and consistency setting while creating

// COMMAND ----------

// MAGIC %md
// MAGIC **REFERENCE:**<br>
// MAGIC Different approaches to creating dataframes:<br>
// MAGIC https://medium.com/@mrpowers/manually-creating-spark-dataframes-b14dae906393
// MAGIC <br><br>
// MAGIC CQL related:<br>
// MAGIC https://www.datastax.com/dev/blog/a-deep-look-to-the-cql-where-clause

// COMMAND ----------

// MAGIC %md
// MAGIC ##### 3.0.1.1. Create a dataframe with 5 records

// COMMAND ----------

// Generate a simple dataset containing five values and
val booksDF = Seq(
   ("b00001", "Arthur Conan Doyle", "A study in scarlet", 1887),
   ("b00023", "Arthur Conan Doyle", "A sign of four", 1890),
   ("b01001", "Arthur Conan Doyle", "The adventures of Sherlock Holmes", 1892),
   ("b00501", "Arthur Conan Doyle", "The memoirs of Sherlock Holmes", 1893),
   ("b00300", "Arthur Conan Doyle", "The hounds of Baskerville", 1901)
).toDF("book_id", "book_author", "book_name", "book_pub_year")

booksDF.printSchema
booksDF.show

// COMMAND ----------

// MAGIC %md
// MAGIC #####3.0.1.2. Save dataframe

// COMMAND ----------

// MAGIC %md
// MAGIC **Consistency level mapping (read and write) from Cassandra to CosmosDB Cassandra API:**<br>
// MAGIC Any (Write Only)->Eventual<br>
// MAGIC One->Eventual<br>
// MAGIC Two->Strong<br>
// MAGIC Three->Strong<br>
// MAGIC Quorum->Strong<br>
// MAGIC All->Strong<br>
// MAGIC Local One->Strong<br>
// MAGIC Local Quorum->Strong<br>
// MAGIC Each Quorum (Write Only)->Strong<br>
// MAGIC SERIAL->Strong<br>
// MAGIC LOCAL_SERIAL->Strong<br>

// COMMAND ----------

//Set individual records to strong consistency
//import com.datastax.spark.connector.writer._

booksDF.write
  .mode("append")
  .format("org.apache.spark.sql.cassandra")
  .options(Map( "table" -> "books", "keyspace" -> "books_ks", "output.consistency.level" -> "ALL", "ttl" -> "10000000"))
  .save()

// COMMAND ----------

// MAGIC %md
// MAGIC #####3.0.1.3. Validate in cqlsh<br>

// COMMAND ----------

// MAGIC %md
// MAGIC <code>**use** books_ks;</code><br>
// MAGIC <code>**select** \* **from** books;</code>

// COMMAND ----------

// MAGIC %md
// MAGIC ##### 3.0.1.4. Create if not exists

// COMMAND ----------

//Quick looks at data prior
sqlContext
  .read
  .format("org.apache.spark.sql.cassandra")
  .options(Map( "table" -> "books", "keyspace" -> "books_ks"))
  .load
  .show

// COMMAND ----------

//sparkConf.set("spark.cassandra.output.ifNotExists", "true")
val booksDF = Seq(
   ("b00001", "Arthur Conan Doyle", "A study in scarlet", 1891,23.00),
   ("b02999", "Arthur Conan Doyle", "A case of identity", 1891,15.00)
).toDF("book_id", "book_author", "book_name", "book_pub_year","book_price")

booksDF.write
  .mode("append")
  .format("org.apache.spark.sql.cassandra")
  .options(Map( "table" -> "books", "keyspace" -> "books_ks", "spark.cassandra.output.consistency.level" -> "ALL", "ttl" -> "10000000" , "spark.cassandra.output.ifNotExists" -> "true"))
  .save()

// COMMAND ----------

//Quick looks at data prior
sqlContext
  .read
  .format("org.apache.spark.sql.cassandra")
  .options(Map( "table" -> "books", "keyspace" -> "books_ks"))
  .load
  .show

// COMMAND ----------

// MAGIC %md
// MAGIC ### 3.0.2. RDD API
// MAGIC Covers per record TTL and consistency setting while creating

// COMMAND ----------

// MAGIC %md
// MAGIC #####3.0.2.1. Create RDD with the same 5 records<br>
// MAGIC Delete previously loaded records<br>

// COMMAND ----------

//Instantiate cassandra connector
val cdbConnector = CassandraConnector(sc)

//Delete from Spark
cdbConnector.withSessionDo(session => session.execute("delete from books_ks.books where book_id in ('b00300','b00001','b00023','b00501','b09999','b01001');"))

// COMMAND ----------

// MAGIC %md
// MAGIC If you prefer to delete from cqlsh-<br>
// MAGIC <code>**USE** books_ks;</code><br>
// MAGIC <code>**delete from** books **where** book_id **in** ('b00300','b00001','b00023','b00501','b09999','b01001');</code>

// COMMAND ----------

val booksRDD = sc.parallelize(Seq(
   ("b00001", "Arthur Conan Doyle", "A study in scarlet", 1887),
   ("b00023", "Arthur Conan Doyle", "A sign of four", 1890),
   ("b01001", "Arthur Conan Doyle", "The adventures of Sherlock Holmes", 1892),
   ("b00501", "Arthur Conan Doyle", "The memoirs of Sherlock Holmes", 1893),
   ("b00300", "Arthur Conan Doyle", "The hounds of Baskerville", 1901)
))
booksRDD.take(2).foreach(println)

// COMMAND ----------

// MAGIC %md
// MAGIC ##### 3.0.2.2. Save RDD to CosmosDB-Cassandra API

// COMMAND ----------

import com.datastax.spark.connector.writer._
booksRDD.saveToCassandra("books_ks", "books", SomeColumns("book_id", "book_author", "book_name", "book_pub_year"),writeConf = WriteConf(ttl = TTLOption.constant(900000),consistencyLevel = ConsistencyLevel.ALL))

// COMMAND ----------

// MAGIC %md
// MAGIC #####3.0.2.3. Validate in cqlsh<br>

// COMMAND ----------

// MAGIC %md
// MAGIC <code>**use** books_ks;</code><br>
// MAGIC <code>**select** \* **from** books;</code><br>
// MAGIC <code>**select** book_id,book_name,TTL(book_name) **from** books;</code>

// COMMAND ----------

// MAGIC %md
// MAGIC #####3.0.2.3. Validate in cqlsh<br>

// COMMAND ----------

booksRDD.saveAsCassandraTable("books_ks", "books_new", SomeColumns("book_id", "book_author", "book_name", "book_pub_year"))

// COMMAND ----------

// MAGIC %md
// MAGIC ### 3.0.3. CQL
// MAGIC This is just FYI.  Prefer the Dataframe/RDD API for scale, or use this in a foreachpartition

// COMMAND ----------

// MAGIC %md
// MAGIC #####3.0.3.1. Insert with CQL from driver<br>

// COMMAND ----------

//Instantiate cassandra connector
val cdbConnector = CassandraConnector(sc)
cdbConnector.withSessionDo(
  session => session.execute("INSERT INTO books_ks.books(book_id, book_name) values('b000009','The Red-Headed League') WITH TTL 900000;"))

// COMMAND ----------

// MAGIC %md
// MAGIC #####3.0.3.2. Validate in cqlsh<br>

// COMMAND ----------

// MAGIC %md
// MAGIC <code>**use** books_ks;</code><br>
// MAGIC <code>**select** \* **from** books;</code>

// COMMAND ----------
