/*
 * Copyright (2020-present) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.standalone.internal

import java.math.{BigDecimal => JBigDecimal}
import java.sql.Timestamp
import java.util.{List => JList, Map => JMap, TimeZone}
import java.util.Arrays.{asList => asJList}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import com.fasterxml.jackson.core.JsonParseException
import org.apache.hadoop.conf.Configuration
import org.scalatest.FunSuite

import io.delta.standalone.DeltaLog
import io.delta.standalone.data.{CloseableIterator, RowRecord => JRowRecord}
import io.delta.standalone.types._

import io.delta.standalone.internal.data.RowParquetRecordImpl
import io.delta.standalone.internal.sources.StandaloneHadoopConf
import io.delta.standalone.internal.util.DataTypeParser
import io.delta.standalone.internal.util.GoldenTableUtils._

/**
 * Instead of using Spark in this project to WRITE data and log files for tests, we have
 * io.delta.golden.GoldenTables do it instead. During tests, we then refer by name to specific
 * golden tables that that class is responsible for generating ahead of time. This allows us to
 * focus on READING only so that we may fully decouple from Spark and not have it as a dependency.
 *
 * See io.delta.golden.GoldenTables for documentation on how to ensure that the needed files have
 * been generated.
 */
class DeltaDataReaderSuite extends FunSuite {

  test("read - primitives") {
    withLogForGoldenTable("data-reader-primitives") { log =>
      val recordIter = log.snapshot().open()
      var count = 0
      var checkNulls = false
      while (recordIter.hasNext) {
        val row = recordIter.next()
        if (row.isNullAt("as_int")) {
          assert(row.isNullAt("as_int"))
          intercept[NullPointerException](row.getInt("as_int"))
          assert(row.isNullAt("as_long"))
          intercept[NullPointerException](row.getInt("as_long"))
          assert(row.isNullAt("as_byte"))
          intercept[NullPointerException](row.getInt("as_byte"))
          assert(row.isNullAt("as_short"))
          intercept[NullPointerException](row.getInt("as_short"))
          assert(row.isNullAt("as_boolean"))
          intercept[NullPointerException](row.getInt("as_boolean"))
          assert(row.isNullAt("as_float"))
          intercept[NullPointerException](row.getInt("as_float"))
          assert(row.isNullAt("as_double"))
          intercept[NullPointerException](row.getInt("as_double"))
          assert(row.isNullAt("as_string"))
          assert(row.getString("as_string") == null)
          assert(row.isNullAt("as_binary"))
          assert(row.getBinary("as_binary") == null)
          assert(row.isNullAt("as_big_decimal"))
          assert(row.getBigDecimal("as_big_decimal") == null)
          checkNulls = true
        } else {
          val i = row.getInt("as_int")
          assert(row.getLong("as_long") == i.longValue)
          assert(row.getByte("as_byte") == i.toByte)
          assert(row.getShort("as_short") == i.shortValue)
          assert(row.getBoolean("as_boolean") == (i % 2 == 0))
          assert(row.getFloat("as_float") == i.floatValue)
          assert(row.getDouble("as_double") == i.doubleValue)
          assert(row.getString("as_string") == i.toString)
          assert(row.getBinary("as_binary") sameElements Array[Byte](i.toByte, i.toByte))
          assert(row.getBigDecimal("as_big_decimal") == new JBigDecimal(i))
        }
        count += 1
      }

      assert(count == 11)
      assert(checkNulls, "didn't check null values for primitive types. " +
        "Please check if the generated table is correct")
    }
  }

  test("read - date types") {
    Seq("UTC", "Iceland", "PST", "America/Los_Angeles", "Etc/GMT+9", "Asia/Beirut",
      "JST").foreach { timeZoneId =>
      withGoldenTable(s"data-reader-date-types-$timeZoneId") { tablePath =>
        val timeZone = TimeZone.getTimeZone(timeZoneId)
        TimeZone.setDefault(timeZone)

        val timestamp = Timestamp.valueOf("2020-01-01 08:09:10")
        val date = java.sql.Date.valueOf("2020-01-01")

        val hadoopConf = new Configuration()
        hadoopConf.set(StandaloneHadoopConf.PARQUET_DATA_TIME_ZONE_ID, timeZoneId)

        val log = DeltaLog.forTable(hadoopConf, tablePath)
        val recordIter = log.snapshot().open()

        if (!recordIter.hasNext) fail(s"No row record for timeZoneId $timeZoneId")

        val row = recordIter.next()

        assert(row.getTimestamp("timestamp").equals(timestamp))
        assert(row.getDate("date").equals(date))

        recordIter.close()
      }
    }
  }

  test("read - array of primitives") {
    withLogForGoldenTable("data-reader-array-primitives") { log =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val list = row.getList[Int]("as_array_int")
        val i = list.get(0)

        assert(row.getList[Long]("as_array_long") == asJList(i.toLong))
        assert(row.getList[Byte]("as_array_byte") == asJList(i.toByte))
        assert(row.getList[Short]("as_array_short") == asJList(i.shortValue))
        assert(row.getList[Boolean]("as_array_boolean") == asJList(i % 2 == 0))
        assert(row.getList[Float]("as_array_float") == asJList(i.floatValue))
        assert(row.getList[Double]("as_array_double") == asJList(i.doubleValue))
        assert(row.getList[String]("as_array_string") == asJList(i.toString))
        assert(row.getList[Array[Byte]]("as_array_binary").get(0) sameElements
          Array(i.toByte, i.toByte))
        assert(row.getList[JBigDecimal]("as_array_big_decimal") == asJList(new JBigDecimal(i)))
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - array of complex objects") {
    withLogForGoldenTable("data-reader-array-complex-objects") { log =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val i = row.getInt("i")
        assert(
          row.getList[JList[JList[Int]]]("3d_int_list") ==
          asJList(
            asJList(asJList(i, i, i), asJList(i, i, i)),
            asJList(asJList(i, i, i), asJList(i, i, i))
          )
        )

        assert(
          row.getList[JList[JList[JList[Int]]]]("4d_int_list") ==
            asJList(
              asJList(
                asJList(asJList(i, i, i), asJList(i, i, i)),
                asJList(asJList(i, i, i), asJList(i, i, i))
              ),
              asJList(
                asJList(asJList(i, i, i), asJList(i, i, i)),
                asJList(asJList(i, i, i), asJList(i, i, i))
              )
            )
        )

        assert(
          row.getList[JMap[String, Long]]("list_of_maps") ==
          asJList(
            Map[String, Long](i.toString -> i.toLong).asJava,
            Map[String, Long](i.toString -> i.toLong).asJava
          )
        )

        val recordList = row.getList[JRowRecord]("list_of_records")
        recordList.asScala.foreach(nestedRow => assert(nestedRow.getInt("val") == i))
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - map") {
    withLogForGoldenTable("data-reader-map") { log =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val i = row.getInt("i")
        assert(row.getMap[Int, Int]("a").equals(Map(i -> i).asJava))
        assert(row.getMap[Long, Byte]("b").equals(Map(i.toLong -> i.toByte).asJava))
        assert(row.getMap[Short, Boolean]("c").equals(Map(i.toShort -> (i % 2 == 0)).asJava))
        assert(row.getMap[Float, Double]("d").equals(Map(i.toFloat -> i.toDouble).asJava))
        assert(
          row.getMap[String, JBigDecimal]("e").equals(Map(i.toString -> new JBigDecimal(i)).asJava)
        )

        val mapOfRecordList = row.getMap[Int, java.util.List[JRowRecord]]("f")
        val recordList = mapOfRecordList.get(i)
        recordList.asScala.foreach(nestedRow => assert(nestedRow.getInt("val") == i))
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - nested struct") {
    withLogForGoldenTable("data-reader-nested-struct") { log =>
      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        val i = row.getInt("b")
        val nestedStruct = row.getRecord("a")
        assert(nestedStruct.getString("aa") == i.toString)
        assert(nestedStruct.getString("ab") == i.toString)

        val nestedNestedStruct = nestedStruct.getRecord("ac")
        assert(nestedNestedStruct.getInt("aca") == i)
        assert(nestedNestedStruct.getLong("acb") == i.toLong)
        count += 1
      }

      assert(count == 10)
    }
  }

  test("read - nullable field, invalid schema column key") {
    withLogForGoldenTable("data-reader-nullable-field-invalid-schema-key") { log =>
      val recordIter = log.snapshot().open()

      if (!recordIter.hasNext) fail(s"No row record")

      val row = recordIter.next()
      row.getList[String]("array_can_contain_null").asScala.foreach(elem => assert(elem == null))

      val e = intercept[IllegalArgumentException] {
        row.getInt("foo_key_does_not_exist")
      }
      assert(e.getMessage.contains("Field \"foo_key_does_not_exist\" does not exist."))

      recordIter.close()
    }
  }

  /** this also tests reading PARTITIONED data */
  test("test escaped char sequences in path") {
    withLogForGoldenTable("data-reader-escaped-chars") { log =>
      assert(log.snapshot().getAllFiles.asScala.forall(_.getPath.contains("_2=bar")))

      val recordIter = log.snapshot().open()
      var count = 0
      while (recordIter.hasNext) {
        val row = recordIter.next()
        assert(row.getString("_1").contains("foo"))
        count += 1
      }

      assert(count == 3)
    }
  }

  test("test bad type cast") {
    withLogForGoldenTable("data-reader-primitives") { log =>
      val recordIter = log.snapshot().open()
      assertThrows[ClassCastException] {
        var row = recordIter.next()
        while (row.isNullAt("as_big_decimal")) {
          // Skip null values as we don't do type check for null values.
          row = recordIter.next()
        }
        row.getString("as_big_decimal")
      }
    }
  }

  test("correct schema and length") {
    withLogForGoldenTable("data-reader-date-types-UTC") { log =>
      val recordIter = log.snapshot().open()
      if (!recordIter.hasNext) fail(s"No row record")
      val row = recordIter.next()
      assert(row.getLength == 2)

      val expectedSchema = new StructType(Array(
        new StructField("timestamp", new TimestampType),
        new StructField("date", new DateType)
      ))

      assert(row.getSchema == expectedSchema)
    }
  }

  test("data reader can read partition values") {
    withLogForGoldenTable("data-reader-partition-values") { log =>
      val snapshot = log.update()
      val partitionColumns = snapshot.getMetadata.getPartitionColumns.asScala.toSet
      val recordIter = snapshot.open()

      if (!recordIter.hasNext) fail(s"No row record")

      while (recordIter.hasNext) {
        val row = recordIter.next()
        assert(row.getLength == 15)

        assert(!row.isNullAt("value"))

        if (row.getString("value") == "2") { // null partition columns
          for (fieldName <- row.getSchema.getFieldNames.filter(partitionColumns.contains)) {
            assert(row.isNullAt(fieldName))
          }
        } else {
          doMatch(row, row.getString("value").toInt);
        }
      }
    }
  }

  private def doMatch(row: JRowRecord, i: Int): Unit = {
    assert(row.getInt("as_int") == i)
    assert(row.getLong("as_long") == i.longValue)
    assert(row.getByte("as_byte") == i.toByte)
    assert(row.getShort("as_short") == i.shortValue)
    assert(row.getBoolean("as_boolean") == (i % 2 == 0))
    assert(row.getFloat("as_float") == i.floatValue)
    assert(row.getDouble("as_double") == i.doubleValue)
    assert(row.getString("as_string") == i.toString)
    assert(row.getString("as_string_lit_null") == "null")
    assert(row.getDate("as_date") == java.sql.Date.valueOf("2021-09-08"))
    assert(row.getTimestamp("as_timestamp") == java.sql.Timestamp.valueOf("2021-09-08 11:11:11"))
    assert(row.getBigDecimal("as_big_decimal") == new JBigDecimal(i))

    val recordsList = row.getList[JRowRecord]("as_list_of_records")
    assert(recordsList.get(0).asInstanceOf[RowParquetRecordImpl].partitionValues.isEmpty)
    assert(recordsList.get(0).getInt("val") == i)

    val nestedStruct = row.getRecord("as_nested_struct")
    assert(nestedStruct.asInstanceOf[RowParquetRecordImpl].partitionValues.isEmpty)
    val nestedNestedStruct = nestedStruct.getRecord("ac")
    assert(nestedNestedStruct.asInstanceOf[RowParquetRecordImpl].partitionValues.isEmpty)
  }

  private def checkDataTypeToJsonFromJson(dataType: DataType): Unit = {
    test(s"DataType to Json and from Json - $dataType") {
      assert(DataTypeParser.fromJson(dataType.toJson) === dataType) // internal API
      assert(DataType.fromJson(dataType.toJson) === dataType) // public API
    }

    test(s"DataType inside StructType to Json and from Json - $dataType") {
      val field1 = new StructField("foo", dataType, true)
      val field2 = new StructField("bar", dataType, true)
      val struct = new StructType(Array(field1, field2))
      assert(DataTypeParser.fromJson(struct.toJson) === struct) // internal API
      assert(DataType.fromJson(struct.toJson) === struct) // public API
    }
  }

  checkDataTypeToJsonFromJson(new BooleanType)
  checkDataTypeToJsonFromJson(new ByteType)
  checkDataTypeToJsonFromJson(new ShortType)
  checkDataTypeToJsonFromJson(new IntegerType)
  checkDataTypeToJsonFromJson(new LongType)
  checkDataTypeToJsonFromJson(new FloatType)
  checkDataTypeToJsonFromJson(new DoubleType)
  checkDataTypeToJsonFromJson(new DecimalType(10, 5))
  checkDataTypeToJsonFromJson(DecimalType.USER_DEFAULT)
  checkDataTypeToJsonFromJson(new DateType)
  checkDataTypeToJsonFromJson(new TimestampType)
  checkDataTypeToJsonFromJson(new StringType)
  checkDataTypeToJsonFromJson(new BinaryType)
  checkDataTypeToJsonFromJson(new ArrayType(new DoubleType, true))
  checkDataTypeToJsonFromJson(new ArrayType(new StringType, false))
  checkDataTypeToJsonFromJson(new MapType(new IntegerType, new StringType, true))
  checkDataTypeToJsonFromJson(
    new MapType(
      new IntegerType,
      new ArrayType(new DoubleType, true),
      false))

  test("toJson fromJson for field metadata") {
    val emptyMetadata = FieldMetadata.builder().build()
    val singleStringMetadata = FieldMetadata.builder().putString("test", "test_value").build()
    val singleBooleanMetadata = FieldMetadata.builder().putBoolean("test", true).build()
    val singleIntegerMetadata = FieldMetadata.builder().putLong("test", 2L).build()
    val singleDoubleMetadata = FieldMetadata.builder().putDouble("test", 2.0).build()
    val singleMapMetadata = FieldMetadata.builder().putMetadata("test_outside",
      FieldMetadata.builder().putString("test_inside", "test_inside_value").build()).build()
    val singleListMetadata = FieldMetadata.builder().putLongArray("test", Array(0L, 1L, 2L)).build()
    val multipleEntriesMetadata = FieldMetadata.builder().putString("test", "test_value")
      .putDouble("test", 2.0).putLongArray("test", Array(0L, 1L, 2L)).build()

    val field_array = Array(
      new StructField("emptyMetadata", new BooleanType, true, emptyMetadata),
      new StructField("singleStringMetadata", new BooleanType, true, singleStringMetadata),
      new StructField("singleBooleanMetadata", new BooleanType, true, singleBooleanMetadata),
      new StructField("singleIntegerMetadata", new BooleanType, true, singleIntegerMetadata),
      new StructField("singleDoubleMetadata", new BooleanType, true, singleDoubleMetadata),
      new StructField("singleMapMetadata", new BooleanType, true, singleMapMetadata),
      new StructField("singleListMetadata", new BooleanType, true, singleListMetadata),
      new StructField("multipleEntriesMetadata", new BooleanType, true, multipleEntriesMetadata))
    val struct = new StructType(field_array)
    assert(struct == DataTypeParser.fromJson(struct.toJson())) // internal API
    assert(struct == DataType.fromJson(struct.toJson)) // public API
  }

  test("DataType.fromJson - invalid json") {
    assertThrows[JsonParseException] {
      DataType.fromJson("foo" + new BooleanType().toJson + "bar")
    }
    assertThrows[JsonParseException] {
      DataType.fromJson(
        new StructType()
          .add("col1", new IntegerType())
          .add("col2", new StringType())
          .toJson
          .replaceAll("\"", "?")
      )
    }
  }

  test("#124: getBigDecimal decode correctly for LongValue") {
    withLogForGoldenTable("124-decimal-decode-bug") { log =>
      val recordIter = log.snapshot().open()
      val row = recordIter.next()
      assert(row.getBigDecimal("large_decimal") == new JBigDecimal(1000000))
      assert(!recordIter.hasNext)
    }
  }

  // scalastyle:off line.size.limit
  test("#125: CloseableParquetDataIterator should not stop iteration when processing an empty file") {
    // scalastyle:on line.size.limit
    withLogForGoldenTable("125-iterator-bug") { log =>
      var datas = new ListBuffer[Int]()
      var dataIter: CloseableIterator[JRowRecord] = null
      try {
        dataIter = log.update().open()
        while (dataIter.hasNext) {
          datas += dataIter.next().getInt("col1")
        }

        assert(datas.length == 5)
        assert(datas.toSet == Set(1, 2, 3, 4, 5))
      } finally {
        if (null != dataIter) {
          dataIter.close()
        }
      }
    }
  }
}

