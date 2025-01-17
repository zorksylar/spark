/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.parquet

import java.net.URI
import java.util.logging.{Level, Logger => JLogger}
import java.util.{List => JList}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.{Failure, Try}

import com.google.common.base.Objects
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.parquet.filter2.predicate.FilterApi
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.ContextUtil
import org.apache.parquet.hadoop.{ParquetOutputCommitter, ParquetRecordReader, _}
import org.apache.parquet.schema.MessageType
import org.apache.parquet.{Log => ParquetLog}

import org.apache.spark.{Logging, Partition => SparkPartition, SparkException}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.{SqlNewHadoopPartition, SqlNewHadoopRDD, RDD}
import org.apache.spark.rdd.RDD._
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources.PartitionSpec
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.{DataType, StructType}
import org.apache.spark.util.{SerializableConfiguration, Utils}


private[sql] class DefaultSource extends HadoopFsRelationProvider {
  override def createRelation(
      sqlContext: SQLContext,
      paths: Array[String],
      schema: Option[StructType],
      partitionColumns: Option[StructType],
      parameters: Map[String, String]): HadoopFsRelation = {
    new ParquetRelation(paths, schema, None, partitionColumns, parameters)(sqlContext)
  }
}

// NOTE: This class is instantiated and used on executor side only, no need to be serializable.
private[sql] class ParquetOutputWriter(path: String, context: TaskAttemptContext)
  extends OutputWriterInternal {

  private val recordWriter: RecordWriter[Void, InternalRow] = {
    val outputFormat = {
      new ParquetOutputFormat[InternalRow]() {
        // Here we override `getDefaultWorkFile` for two reasons:
        //
        //  1. To allow appending.  We need to generate unique output file names to avoid
        //     overwriting existing files (either exist before the write job, or are just written
        //     by other tasks within the same write job).
        //
        //  2. To allow dynamic partitioning.  Default `getDefaultWorkFile` uses
        //     `FileOutputCommitter.getWorkPath()`, which points to the base directory of all
        //     partitions in the case of dynamic partitioning.
        override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
          val uniqueWriteJobId = context.getConfiguration.get("spark.sql.sources.writeJobUUID")
          val split = context.getTaskAttemptID.getTaskID.getId
          new Path(path, f"part-r-$split%05d-$uniqueWriteJobId$extension")
        }
      }
    }

    outputFormat.getRecordWriter(context)
  }

  override def writeInternal(row: InternalRow): Unit = recordWriter.write(null, row)

  override def close(): Unit = recordWriter.close(context)
}

private[sql] class ParquetRelation(
    override val paths: Array[String],
    private val maybeDataSchema: Option[StructType],
    // This is for metastore conversion.
    private val maybePartitionSpec: Option[PartitionSpec],
    override val userDefinedPartitionColumns: Option[StructType],
    parameters: Map[String, String])(
    val sqlContext: SQLContext)
  extends HadoopFsRelation(maybePartitionSpec)
  with Logging {

  private[sql] def this(
      paths: Array[String],
      maybeDataSchema: Option[StructType],
      maybePartitionSpec: Option[PartitionSpec],
      parameters: Map[String, String])(
      sqlContext: SQLContext) = {
    this(
      paths,
      maybeDataSchema,
      maybePartitionSpec,
      maybePartitionSpec.map(_.partitionColumns),
      parameters)(sqlContext)
  }

  // Should we merge schemas from all Parquet part-files?
  private val shouldMergeSchemas =
    parameters
      .get(ParquetRelation.MERGE_SCHEMA)
      .map(_.toBoolean)
      .getOrElse(sqlContext.conf.getConf(SQLConf.PARQUET_SCHEMA_MERGING_ENABLED))

  private val mergeRespectSummaries =
    sqlContext.conf.getConf(SQLConf.PARQUET_SCHEMA_RESPECT_SUMMARIES)

  private val maybeMetastoreSchema = parameters
    .get(ParquetRelation.METASTORE_SCHEMA)
    .map(DataType.fromJson(_).asInstanceOf[StructType])

  private lazy val metadataCache: MetadataCache = {
    val meta = new MetadataCache
    meta.refresh()
    meta
  }

  override def equals(other: Any): Boolean = other match {
    case that: ParquetRelation =>
      val schemaEquality = if (shouldMergeSchemas) {
        this.shouldMergeSchemas == that.shouldMergeSchemas
      } else {
        this.dataSchema == that.dataSchema &&
          this.schema == that.schema
      }

      this.paths.toSet == that.paths.toSet &&
        schemaEquality &&
        this.maybeDataSchema == that.maybeDataSchema &&
        this.partitionColumns == that.partitionColumns

    case _ => false
  }

  override def hashCode(): Int = {
    if (shouldMergeSchemas) {
      Objects.hashCode(
        Boolean.box(shouldMergeSchemas),
        paths.toSet,
        maybeDataSchema,
        partitionColumns)
    } else {
      Objects.hashCode(
        Boolean.box(shouldMergeSchemas),
        paths.toSet,
        dataSchema,
        schema,
        maybeDataSchema,
        partitionColumns)
    }
  }

  /** Constraints on schema of dataframe to be stored. */
  private def checkConstraints(schema: StructType): Unit = {
    if (schema.fieldNames.length != schema.fieldNames.distinct.length) {
      val duplicateColumns = schema.fieldNames.groupBy(identity).collect {
        case (x, ys) if ys.length > 1 => "\"" + x + "\""
      }.mkString(", ")
      throw new AnalysisException(s"Duplicate column(s) : $duplicateColumns found, " +
        s"cannot save to parquet format")
    }
  }

  override def dataSchema: StructType = {
    val schema = maybeDataSchema.getOrElse(metadataCache.dataSchema)
    // check if schema satisfies the constraints
    // before moving forward
    checkConstraints(schema)
    schema
  }

  override private[sql] def refresh(): Unit = {
    super.refresh()
    metadataCache.refresh()
  }

  // Parquet data source always uses Catalyst internal representations.
  override val needConversion: Boolean = false

  override def sizeInBytes: Long = metadataCache.dataStatuses.map(_.getLen).sum

  override def prepareJobForWrite(job: Job): OutputWriterFactory = {
    val conf = ContextUtil.getConfiguration(job)

    val committerClass =
      conf.getClass(
        SQLConf.PARQUET_OUTPUT_COMMITTER_CLASS.key,
        classOf[ParquetOutputCommitter],
        classOf[ParquetOutputCommitter])

    if (conf.get(SQLConf.PARQUET_OUTPUT_COMMITTER_CLASS.key) == null) {
      logInfo("Using default output committer for Parquet: " +
        classOf[ParquetOutputCommitter].getCanonicalName)
    } else {
      logInfo("Using user defined output committer for Parquet: " + committerClass.getCanonicalName)
    }

    conf.setClass(
      SQLConf.OUTPUT_COMMITTER_CLASS.key,
      committerClass,
      classOf[ParquetOutputCommitter])

    // We're not really using `ParquetOutputFormat[Row]` for writing data here, because we override
    // it in `ParquetOutputWriter` to support appending and dynamic partitioning.  The reason why
    // we set it here is to setup the output committer class to `ParquetOutputCommitter`, which is
    // bundled with `ParquetOutputFormat[Row]`.
    job.setOutputFormatClass(classOf[ParquetOutputFormat[Row]])

    // TODO There's no need to use two kinds of WriteSupport
    // We should unify them. `SpecificMutableRow` can process both atomic (primitive) types and
    // complex types.
    val writeSupportClass =
      if (dataSchema.map(_.dataType).forall(ParquetTypesConverter.isPrimitiveType)) {
        classOf[MutableRowWriteSupport]
      } else {
        classOf[RowWriteSupport]
      }

    ParquetOutputFormat.setWriteSupportClass(job, writeSupportClass)
    RowWriteSupport.setSchema(dataSchema.toAttributes, conf)

    // Sets compression scheme
    conf.set(
      ParquetOutputFormat.COMPRESSION,
      ParquetRelation
        .shortParquetCompressionCodecNames
        .getOrElse(
          sqlContext.conf.parquetCompressionCodec.toUpperCase,
          CompressionCodecName.UNCOMPRESSED).name())

    new OutputWriterFactory {
      override def newInstance(
          path: String, dataSchema: StructType, context: TaskAttemptContext): OutputWriter = {
        new ParquetOutputWriter(path, context)
      }
    }
  }

  override def buildScan(
      requiredColumns: Array[String],
      filters: Array[Filter],
      inputFiles: Array[FileStatus],
      broadcastedConf: Broadcast[SerializableConfiguration]): RDD[Row] = {
    val useMetadataCache = sqlContext.getConf(SQLConf.PARQUET_CACHE_METADATA)
    val parquetFilterPushDown = sqlContext.conf.parquetFilterPushDown
    val assumeBinaryIsString = sqlContext.conf.isParquetBinaryAsString
    val assumeInt96IsTimestamp = sqlContext.conf.isParquetINT96AsTimestamp
    val followParquetFormatSpec = sqlContext.conf.followParquetFormatSpec

    // Create the function to set variable Parquet confs at both driver and executor side.
    val initLocalJobFuncOpt =
      ParquetRelation.initializeLocalJobFunc(
        requiredColumns,
        filters,
        dataSchema,
        useMetadataCache,
        parquetFilterPushDown,
        assumeBinaryIsString,
        assumeInt96IsTimestamp,
        followParquetFormatSpec) _

    // Create the function to set input paths at the driver side.
    val setInputPaths = ParquetRelation.initializeDriverSideJobFunc(inputFiles) _

    Utils.withDummyCallSite(sqlContext.sparkContext) {
      new SqlNewHadoopRDD(
        sc = sqlContext.sparkContext,
        broadcastedConf = broadcastedConf,
        initDriverSideJobFuncOpt = Some(setInputPaths),
        initLocalJobFuncOpt = Some(initLocalJobFuncOpt),
        inputFormatClass = classOf[ParquetInputFormat[InternalRow]],
        valueClass = classOf[InternalRow]) {

        val cacheMetadata = useMetadataCache

        @transient val cachedStatuses = inputFiles.map { f =>
          // In order to encode the authority of a Path containing special characters such as '/'
          // (which does happen in some S3N credentials), we need to use the string returned by the
          // URI of the path to create a new Path.
          val pathWithEscapedAuthority = escapePathUserInfo(f.getPath)
          new FileStatus(
            f.getLen, f.isDir, f.getReplication, f.getBlockSize, f.getModificationTime,
            f.getAccessTime, f.getPermission, f.getOwner, f.getGroup, pathWithEscapedAuthority)
        }.toSeq

        private def escapePathUserInfo(path: Path): Path = {
          val uri = path.toUri
          new Path(new URI(
            uri.getScheme, uri.getRawUserInfo, uri.getHost, uri.getPort, uri.getPath,
            uri.getQuery, uri.getFragment))
        }

        // Overridden so we can inject our own cached files statuses.
        override def getPartitions: Array[SparkPartition] = {
          val inputFormat = new ParquetInputFormat[InternalRow] {
            override def listStatus(jobContext: JobContext): JList[FileStatus] = {
              if (cacheMetadata) cachedStatuses else super.listStatus(jobContext)
            }
          }

          val jobContext = newJobContext(getConf(isDriverSide = true), jobId)
          val rawSplits = inputFormat.getSplits(jobContext)

          Array.tabulate[SparkPartition](rawSplits.size) { i =>
            new SqlNewHadoopPartition(id, i, rawSplits(i).asInstanceOf[InputSplit with Writable])
          }
        }
      }.asInstanceOf[RDD[Row]]  // type erasure hack to pass RDD[InternalRow] as RDD[Row]
    }
  }

  private class MetadataCache {
    // `FileStatus` objects of all "_metadata" files.
    private var metadataStatuses: Array[FileStatus] = _

    // `FileStatus` objects of all "_common_metadata" files.
    private var commonMetadataStatuses: Array[FileStatus] = _

    // `FileStatus` objects of all data files (Parquet part-files).
    var dataStatuses: Array[FileStatus] = _

    // Schema of the actual Parquet files, without partition columns discovered from partition
    // directory paths.
    var dataSchema: StructType = null

    // Schema of the whole table, including partition columns.
    var schema: StructType = _

    // Cached leaves
    var cachedLeaves: Set[FileStatus] = null

    /**
     * Refreshes `FileStatus`es, footers, partition spec, and table schema.
     */
    def refresh(): Unit = {
      val currentLeafStatuses = cachedLeafStatuses()

      // Check if cachedLeafStatuses is changed or not
      val leafStatusesChanged = (cachedLeaves == null) ||
        !cachedLeaves.equals(currentLeafStatuses)

      if (leafStatusesChanged) {
        cachedLeaves = currentLeafStatuses.toIterator.toSet

        // Lists `FileStatus`es of all leaf nodes (files) under all base directories.
        val leaves = currentLeafStatuses.filter { f =>
          isSummaryFile(f.getPath) ||
            !(f.getPath.getName.startsWith("_") || f.getPath.getName.startsWith("."))
        }.toArray

        dataStatuses = leaves.filterNot(f => isSummaryFile(f.getPath))
        metadataStatuses =
          leaves.filter(_.getPath.getName == ParquetFileWriter.PARQUET_METADATA_FILE)
        commonMetadataStatuses =
          leaves.filter(_.getPath.getName == ParquetFileWriter.PARQUET_COMMON_METADATA_FILE)

        dataSchema = {
          val dataSchema0 = maybeDataSchema
            .orElse(readSchema())
            .orElse(maybeMetastoreSchema)
            .getOrElse(throw new AnalysisException(
              s"Failed to discover schema of Parquet file(s) in the following location(s):\n" +
                paths.mkString("\n\t")))

          // If this Parquet relation is converted from a Hive Metastore table, must reconcile case
          // case insensitivity issue and possible schema mismatch (probably caused by schema
          // evolution).
          maybeMetastoreSchema
            .map(ParquetRelation.mergeMetastoreParquetSchema(_, dataSchema0))
            .getOrElse(dataSchema0)
        }
      }
    }

    private def isSummaryFile(file: Path): Boolean = {
      file.getName == ParquetFileWriter.PARQUET_COMMON_METADATA_FILE ||
        file.getName == ParquetFileWriter.PARQUET_METADATA_FILE
    }

    private def readSchema(): Option[StructType] = {
      // Sees which file(s) we need to touch in order to figure out the schema.
      //
      // Always tries the summary files first if users don't require a merged schema.  In this case,
      // "_common_metadata" is more preferable than "_metadata" because it doesn't contain row
      // groups information, and could be much smaller for large Parquet files with lots of row
      // groups.  If no summary file is available, falls back to some random part-file.
      //
      // NOTE: Metadata stored in the summary files are merged from all part-files.  However, for
      // user defined key-value metadata (in which we store Spark SQL schema), Parquet doesn't know
      // how to merge them correctly if some key is associated with different values in different
      // part-files.  When this happens, Parquet simply gives up generating the summary file.  This
      // implies that if a summary file presents, then:
      //
      //   1. Either all part-files have exactly the same Spark SQL schema, or
      //   2. Some part-files don't contain Spark SQL schema in the key-value metadata at all (thus
      //      their schemas may differ from each other).
      //
      // Here we tend to be pessimistic and take the second case into account.  Basically this means
      // we can't trust the summary files if users require a merged schema, and must touch all part-
      // files to do the merge.
      val filesToTouch =
        if (shouldMergeSchemas) {
          // Also includes summary files, 'cause there might be empty partition directories.

          // If mergeRespectSummaries config is true, we assume that all part-files are the same for
          // their schema with summary files, so we ignore them when merging schema.
          // If the config is disabled, which is the default setting, we merge all part-files.
          // In this mode, we only need to merge schemas contained in all those summary files.
          // You should enable this configuration only if you are very sure that for the parquet
          // part-files to read there are corresponding summary files containing correct schema.

          val needMerged: Seq[FileStatus] =
            if (mergeRespectSummaries) {
              Seq()
            } else {
              dataStatuses
            }
          (metadataStatuses ++ commonMetadataStatuses ++ needMerged).toSeq
        } else {
          // Tries any "_common_metadata" first. Parquet files written by old versions or Parquet
          // don't have this.
          commonMetadataStatuses.headOption
            // Falls back to "_metadata"
            .orElse(metadataStatuses.headOption)
            // Summary file(s) not found, the Parquet file is either corrupted, or different part-
            // files contain conflicting user defined metadata (two or more values are associated
            // with a same key in different files).  In either case, we fall back to any of the
            // first part-file, and just assume all schemas are consistent.
            .orElse(dataStatuses.headOption)
            .toSeq
        }

      assert(
        filesToTouch.nonEmpty || maybeDataSchema.isDefined || maybeMetastoreSchema.isDefined,
        "No predefined schema found, " +
          s"and no Parquet data files or summary files found under ${paths.mkString(", ")}.")

      ParquetRelation.mergeSchemasInParallel(filesToTouch, sqlContext)
    }
  }
}

private[sql] object ParquetRelation extends Logging {
  // Whether we should merge schemas collected from all Parquet part-files.
  private[sql] val MERGE_SCHEMA = "mergeSchema"

  // Hive Metastore schema, used when converting Metastore Parquet tables.  This option is only used
  // internally.
  private[sql] val METASTORE_SCHEMA = "metastoreSchema"

  /** This closure sets various Parquet configurations at both driver side and executor side. */
  private[parquet] def initializeLocalJobFunc(
      requiredColumns: Array[String],
      filters: Array[Filter],
      dataSchema: StructType,
      useMetadataCache: Boolean,
      parquetFilterPushDown: Boolean,
      assumeBinaryIsString: Boolean,
      assumeInt96IsTimestamp: Boolean,
      followParquetFormatSpec: Boolean)(job: Job): Unit = {
    val conf = job.getConfiguration
    conf.set(ParquetInputFormat.READ_SUPPORT_CLASS, classOf[CatalystReadSupport].getName)

    // Try to push down filters when filter push-down is enabled.
    if (parquetFilterPushDown) {
      filters
        // Collects all converted Parquet filter predicates. Notice that not all predicates can be
        // converted (`ParquetFilters.createFilter` returns an `Option`). That's why a `flatMap`
        // is used here.
        .flatMap(ParquetFilters.createFilter(dataSchema, _))
        .reduceOption(FilterApi.and)
        .foreach(ParquetInputFormat.setFilterPredicate(conf, _))
    }

    conf.set(CatalystReadSupport.SPARK_ROW_REQUESTED_SCHEMA, {
      val requestedSchema = StructType(requiredColumns.map(dataSchema(_)))
      CatalystSchemaConverter.checkFieldNames(requestedSchema).json
    })

    conf.set(
      RowWriteSupport.SPARK_ROW_SCHEMA,
      CatalystSchemaConverter.checkFieldNames(dataSchema).json)

    // Tell FilteringParquetRowInputFormat whether it's okay to cache Parquet and FS metadata
    conf.setBoolean(SQLConf.PARQUET_CACHE_METADATA.key, useMetadataCache)

    // Sets flags for Parquet schema conversion
    conf.setBoolean(SQLConf.PARQUET_BINARY_AS_STRING.key, assumeBinaryIsString)
    conf.setBoolean(SQLConf.PARQUET_INT96_AS_TIMESTAMP.key, assumeInt96IsTimestamp)
    conf.setBoolean(SQLConf.PARQUET_FOLLOW_PARQUET_FORMAT_SPEC.key, followParquetFormatSpec)
  }

  /** This closure sets input paths at the driver side. */
  private[parquet] def initializeDriverSideJobFunc(
      inputFiles: Array[FileStatus])(job: Job): Unit = {
    // We side the input paths at the driver side.
    logInfo(s"Reading Parquet file(s) from ${inputFiles.map(_.getPath).mkString(", ")}")
    if (inputFiles.nonEmpty) {
      FileInputFormat.setInputPaths(job, inputFiles.map(_.getPath): _*)
    }
  }

  private[parquet] def readSchema(
      footers: Seq[Footer], sqlContext: SQLContext): Option[StructType] = {

    def parseParquetSchema(schema: MessageType): StructType = {
      val converter = new CatalystSchemaConverter(
        sqlContext.conf.isParquetBinaryAsString,
        sqlContext.conf.isParquetBinaryAsString,
        sqlContext.conf.followParquetFormatSpec)

      converter.convert(schema)
    }

    val seen = mutable.HashSet[String]()
    val finalSchemas: Seq[StructType] = footers.flatMap { footer =>
      val metadata = footer.getParquetMetadata.getFileMetaData
      val serializedSchema = metadata
        .getKeyValueMetaData
        .toMap
        .get(CatalystReadSupport.SPARK_METADATA_KEY)
      if (serializedSchema.isEmpty) {
        // Falls back to Parquet schema if no Spark SQL schema found.
        Some(parseParquetSchema(metadata.getSchema))
      } else if (!seen.contains(serializedSchema.get)) {
        seen += serializedSchema.get

        // Don't throw even if we failed to parse the serialized Spark schema. Just fallback to
        // whatever is available.
        Some(Try(DataType.fromJson(serializedSchema.get))
          .recover { case _: Throwable =>
            logInfo(
              s"Serialized Spark schema in Parquet key-value metadata is not in JSON format, " +
                "falling back to the deprecated DataType.fromCaseClassString parser.")
            DataType.fromCaseClassString(serializedSchema.get)
          }
          .recover { case cause: Throwable =>
            logWarning(
              s"""Failed to parse serialized Spark schema in Parquet key-value metadata:
                 |\t$serializedSchema
               """.stripMargin,
              cause)
          }
          .map(_.asInstanceOf[StructType])
          .getOrElse {
            // Falls back to Parquet schema if Spark SQL schema can't be parsed.
            parseParquetSchema(metadata.getSchema)
          })
      } else {
        None
      }
    }

    finalSchemas.reduceOption { (left, right) =>
      try left.merge(right) catch { case e: Throwable =>
        throw new SparkException(s"Failed to merge incompatible schemas $left and $right", e)
      }
    }
  }

  /**
   * Reconciles Hive Metastore case insensitivity issue and data type conflicts between Metastore
   * schema and Parquet schema.
   *
   * Hive doesn't retain case information, while Parquet is case sensitive. On the other hand, the
   * schema read from Parquet files may be incomplete (e.g. older versions of Parquet doesn't
   * distinguish binary and string).  This method generates a correct schema by merging Metastore
   * schema data types and Parquet schema field names.
   */
  private[parquet] def mergeMetastoreParquetSchema(
      metastoreSchema: StructType,
      parquetSchema: StructType): StructType = {
    def schemaConflictMessage: String =
      s"""Converting Hive Metastore Parquet, but detected conflicting schemas. Metastore schema:
         |${metastoreSchema.prettyJson}
         |
         |Parquet schema:
         |${parquetSchema.prettyJson}
       """.stripMargin

    val mergedParquetSchema = mergeMissingNullableFields(metastoreSchema, parquetSchema)

    assert(metastoreSchema.size <= mergedParquetSchema.size, schemaConflictMessage)

    val ordinalMap = metastoreSchema.zipWithIndex.map {
      case (field, index) => field.name.toLowerCase -> index
    }.toMap

    val reorderedParquetSchema = mergedParquetSchema.sortBy(f =>
      ordinalMap.getOrElse(f.name.toLowerCase, metastoreSchema.size + 1))

    StructType(metastoreSchema.zip(reorderedParquetSchema).map {
      // Uses Parquet field names but retains Metastore data types.
      case (mSchema, pSchema) if mSchema.name.toLowerCase == pSchema.name.toLowerCase =>
        mSchema.copy(name = pSchema.name)
      case _ =>
        throw new SparkException(schemaConflictMessage)
    })
  }

  /**
   * Returns the original schema from the Parquet file with any missing nullable fields from the
   * Hive Metastore schema merged in.
   *
   * When constructing a DataFrame from a collection of structured data, the resulting object has
   * a schema corresponding to the union of the fields present in each element of the collection.
   * Spark SQL simply assigns a null value to any field that isn't present for a particular row.
   * In some cases, it is possible that a given table partition stored as a Parquet file doesn't
   * contain a particular nullable field in its schema despite that field being present in the
   * table schema obtained from the Hive Metastore. This method returns a schema representing the
   * Parquet file schema along with any additional nullable fields from the Metastore schema
   * merged in.
   */
  private[parquet] def mergeMissingNullableFields(
      metastoreSchema: StructType,
      parquetSchema: StructType): StructType = {
    val fieldMap = metastoreSchema.map(f => f.name.toLowerCase -> f).toMap
    val missingFields = metastoreSchema
      .map(_.name.toLowerCase)
      .diff(parquetSchema.map(_.name.toLowerCase))
      .map(fieldMap(_))
      .filter(_.nullable)
    StructType(parquetSchema ++ missingFields)
  }

  /**
   * Figures out a merged Parquet schema with a distributed Spark job.
   *
   * Note that locality is not taken into consideration here because:
   *
   *  1. For a single Parquet part-file, in most cases the footer only resides in the last block of
   *     that file.  Thus we only need to retrieve the location of the last block.  However, Hadoop
   *     `FileSystem` only provides API to retrieve locations of all blocks, which can be
   *     potentially expensive.
   *
   *  2. This optimization is mainly useful for S3, where file metadata operations can be pretty
   *     slow.  And basically locality is not available when using S3 (you can't run computation on
   *     S3 nodes).
   */
  def mergeSchemasInParallel(
      filesToTouch: Seq[FileStatus], sqlContext: SQLContext): Option[StructType] = {
    val assumeBinaryIsString = sqlContext.conf.isParquetBinaryAsString
    val assumeInt96IsTimestamp = sqlContext.conf.isParquetINT96AsTimestamp
    val followParquetFormatSpec = sqlContext.conf.followParquetFormatSpec
    val serializedConf = new SerializableConfiguration(sqlContext.sparkContext.hadoopConfiguration)

    // HACK ALERT:
    //
    // Parquet requires `FileStatus`es to read footers.  Here we try to send cached `FileStatus`es
    // to executor side to avoid fetching them again.  However, `FileStatus` is not `Serializable`
    // but only `Writable`.  What makes it worth, for some reason, `FileStatus` doesn't play well
    // with `SerializableWritable[T]` and always causes a weird `IllegalStateException`.  These
    // facts virtually prevents us to serialize `FileStatus`es.
    //
    // Since Parquet only relies on path and length information of those `FileStatus`es to read
    // footers, here we just extract them (which can be easily serialized), send them to executor
    // side, and resemble fake `FileStatus`es there.
    val partialFileStatusInfo = filesToTouch.map(f => (f.getPath.toString, f.getLen))

    // Issues a Spark job to read Parquet schema in parallel.
    val partiallyMergedSchemas =
      sqlContext
        .sparkContext
        .parallelize(partialFileStatusInfo)
        .mapPartitions { iterator =>
          // Resembles fake `FileStatus`es with serialized path and length information.
          val fakeFileStatuses = iterator.map { case (path, length) =>
            new FileStatus(length, false, 0, 0, 0, 0, null, null, null, new Path(path))
          }.toSeq

          // Skips row group information since we only need the schema
          val skipRowGroups = true

          // Reads footers in multi-threaded manner within each task
          val footers =
            ParquetFileReader.readAllFootersInParallel(
              serializedConf.value, fakeFileStatuses, skipRowGroups)

          // Converter used to convert Parquet `MessageType` to Spark SQL `StructType`
          val converter =
            new CatalystSchemaConverter(
              assumeBinaryIsString = assumeBinaryIsString,
              assumeInt96IsTimestamp = assumeInt96IsTimestamp,
              followParquetFormatSpec = followParquetFormatSpec)

          footers.map { footer =>
            ParquetRelation.readSchemaFromFooter(footer, converter)
          }.reduceOption(_ merge _).iterator
        }.collect()

    partiallyMergedSchemas.reduceOption(_ merge _)
  }

  /**
   * Reads Spark SQL schema from a Parquet footer.  If a valid serialized Spark SQL schema string
   * can be found in the file metadata, returns the deserialized [[StructType]], otherwise, returns
   * a [[StructType]] converted from the [[MessageType]] stored in this footer.
   */
  def readSchemaFromFooter(
      footer: Footer, converter: CatalystSchemaConverter): StructType = {
    val fileMetaData = footer.getParquetMetadata.getFileMetaData
    fileMetaData
      .getKeyValueMetaData
      .toMap
      .get(CatalystReadSupport.SPARK_METADATA_KEY)
      .flatMap(deserializeSchemaString)
      .getOrElse(converter.convert(fileMetaData.getSchema))
  }

  private def deserializeSchemaString(schemaString: String): Option[StructType] = {
    // Tries to deserialize the schema string as JSON first, then falls back to the case class
    // string parser (data generated by older versions of Spark SQL uses this format).
    Try(DataType.fromJson(schemaString).asInstanceOf[StructType]).recover {
      case _: Throwable =>
        logInfo(
          s"Serialized Spark schema in Parquet key-value metadata is not in JSON format, " +
            "falling back to the deprecated DataType.fromCaseClassString parser.")
        DataType.fromCaseClassString(schemaString).asInstanceOf[StructType]
    }.recoverWith {
      case cause: Throwable =>
        logWarning(
          "Failed to parse and ignored serialized Spark schema in " +
            s"Parquet key-value metadata:\n\t$schemaString", cause)
        Failure(cause)
    }.toOption
  }

  def enableLogForwarding() {
    // Note: the org.apache.parquet.Log class has a static initializer that
    // sets the java.util.logging Logger for "org.apache.parquet". This
    // checks first to see if there's any handlers already set
    // and if not it creates them. If this method executes prior
    // to that class being loaded then:
    //  1) there's no handlers installed so there's none to
    // remove. But when it IS finally loaded the desired affect
    // of removing them is circumvented.
    //  2) The parquet.Log static initializer calls setUseParentHandlers(false)
    // undoing the attempt to override the logging here.
    //
    // Therefore we need to force the class to be loaded.
    // This should really be resolved by Parquet.
    Utils.classForName(classOf[ParquetLog].getName)

    // Note: Logger.getLogger("parquet") has a default logger
    // that appends to Console which needs to be cleared.
    val parquetLogger = JLogger.getLogger(classOf[ParquetLog].getPackage.getName)
    parquetLogger.getHandlers.foreach(parquetLogger.removeHandler)
    parquetLogger.setUseParentHandlers(true)

    // Disables a WARN log message in ParquetOutputCommitter.  We first ensure that
    // ParquetOutputCommitter is loaded and the static LOG field gets initialized.
    // See https://issues.apache.org/jira/browse/SPARK-5968 for details
    Utils.classForName(classOf[ParquetOutputCommitter].getName)
    JLogger.getLogger(classOf[ParquetOutputCommitter].getName).setLevel(Level.OFF)

    // Similar as above, disables a unnecessary WARN log message in ParquetRecordReader.
    // See https://issues.apache.org/jira/browse/PARQUET-220 for details
    Utils.classForName(classOf[ParquetRecordReader[_]].getName)
    JLogger.getLogger(classOf[ParquetRecordReader[_]].getName).setLevel(Level.OFF)
  }

  // The parquet compression short names
  val shortParquetCompressionCodecNames = Map(
    "NONE"         -> CompressionCodecName.UNCOMPRESSED,
    "UNCOMPRESSED" -> CompressionCodecName.UNCOMPRESSED,
    "SNAPPY"       -> CompressionCodecName.SNAPPY,
    "GZIP"         -> CompressionCodecName.GZIP,
    "LZO"          -> CompressionCodecName.LZO)
}
