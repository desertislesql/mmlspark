// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import org.apache.hadoop.fs.Path
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.ml.param._
import org.apache.spark.ml.util._
import org.apache.spark.ml._
import org.apache.spark.sql._
import org.apache.spark.sql.types._

import scala.collection.mutable.ListBuffer

object CleanMissingData extends DefaultParamsReadable[CleanMissingData] {
  val meanOpt = "Mean"
  val medianOpt = "Median"
  val customOpt = "Custom"
  val modes = Array(meanOpt, medianOpt, customOpt)

  def validateAndTransformSchema(schema: StructType,
                                 inputCols: Array[String],
                                 outputCols: Array[String]): StructType = {
    inputCols.zip(outputCols).foldLeft(schema)((oldSchema, io) => {
      if (oldSchema.fieldNames.contains(io._2)) {
        val index = oldSchema.fieldIndex(io._2)
        val fields = oldSchema.fields
        fields(index) = oldSchema.fields(oldSchema.fieldIndex(io._1))
        StructType(fields)
      } else {
        oldSchema.add(oldSchema.fields(oldSchema.fieldIndex(io._1)))
      }
    })
  }
}

/**
  * Removes missing values from input dataset.
  * The following modes are supported:
  *   Mean   - replaces missings with mean of fit column
  *   Median - replaces missings with approximate median of fit column
  *   Custom - replaces missings with custom value specified by user
  * For mean and median modes, only numeric column types are supported, specifically:
  *   `Int`, `Long`, `Float`, `Double`
  * For custom mode, the types above are supported and additionally:
  *   `String`, `Boolean`
  */
class CleanMissingData(override val uid: String) extends Estimator[CleanMissingDataModel]
  with HasInputCols with HasOutputCols with MMLParams {

  def this() = this(Identifiable.randomUID("CleanMissingData"))

  val cleaningMode = StringParam(this, "cleaningMode", "cleaning mode", CleanMissingData.meanOpt)
  def setCleaningMode(value: String): this.type = set(cleaningMode, value)
  def getCleaningMode: String = $(cleaningMode)

  /**
    * Custom value for imputation, supports numeric, string and boolean types.
    * Date and Timestamp currently not supported.
    */
  val customValue = StringParam(this, "customValue", "custom value for replacement")
  def setCustomValue(value: String): this.type = set(customValue, value)
  def getCustomValue: String = $(customValue)

  /**
    * Fits the dataset, prepares the transformation function.
    *
    * @param dataset The input dataset.
    * @return The model for removing missings.
    */
  override def fit(dataset: Dataset[_]): CleanMissingDataModel = {
    val replacementValues = getReplacementValues(dataset, getInputCols, getOutputCols, getCleaningMode)
    new CleanMissingDataModel(uid, replacementValues, getInputCols, getOutputCols)
  }

  override def copy(extra: ParamMap): Estimator[CleanMissingDataModel] = defaultCopy(extra)

  @DeveloperApi
  override def transformSchema(schema: StructType): StructType =
    CleanMissingData.validateAndTransformSchema(schema, getInputCols, getOutputCols)

  // Only support numeric types (no string, boolean or Date/Timestamp) for numeric imputation
  private def isSupportedTypeForImpute(dataType: DataType): Boolean = dataType.isInstanceOf[NumericType]

  private def verifyColumnsSupported(dataset: Dataset[_], colsToClean: Array[String]): Unit = {
    colsToClean.foreach(columnName =>
      if (!isSupportedTypeForImpute(dataset.schema(columnName).dataType))
        throw new UnsupportedOperationException("Only numeric types supported for numeric imputation"))
  }

  private def getReplacementValues(dataset: Dataset[_],
                                   colsToClean: Array[String],
                                   outputCols: Array[String],
                                   mode: String): Map[String, Any] = {
    import org.apache.spark.sql.functions._
    val columns = colsToClean.map(col => dataset(col))
    val metrics = getCleaningMode match {
      case CleanMissingData.meanOpt => {
        // Verify columns are supported for imputation
        verifyColumnsSupported(dataset, colsToClean)
        val row = dataset.select(columns.map(column => avg(column)): _*).collect()(0)
        rowToValues(row)
      }
      case CleanMissingData.medianOpt => {
        // Verify columns are supported for imputation
        verifyColumnsSupported(dataset, colsToClean)
        val row = dataset.select(columns.map(column => callUDF("percentile_approx", column, lit(0.5))): _*).collect()(0)
        rowToValues(row)
      }
      case CleanMissingData.customOpt => {
        // Note: All column types supported for custom value
        colsToClean.map(col => getCustomValue)
      }
    }
    outputCols.zip(metrics).toMap
  }

  private def rowToValues(row: Row): Array[Double] = {
    val avgs = ListBuffer[Double]()
    for (i <- 0 until row.size) {
      avgs += row.getDouble(i)
    }
    avgs.toArray
  }
}

/**
  * Model produced by [[CleanMissingData]].
  */
class CleanMissingDataModel(val uid: String,
                            val replacementValues: Map[String, Any],
                            val inputCols: Array[String],
                            val outputCols: Array[String])
    extends Model[CleanMissingDataModel] with MLWritable {

  override def write: MLWriter = new CleanMissingDataModel.CleanMissingDataModelWriter(uid,
    replacementValues,
    inputCols,
    outputCols)

  override def copy(extra: ParamMap): CleanMissingDataModel =
    new CleanMissingDataModel(uid, replacementValues, inputCols, outputCols)

  override def transform(dataset: Dataset[_]): DataFrame = {
    val datasetCols = dataset.columns.map(name => dataset(name)).toList
    val datasetInputCols = inputCols.zip(outputCols)
      .flatMap(io =>
        if (io._1 == io._2) {
          None
        } else {
          Some(dataset(io._1).as(io._2))
        }).toList
    val addedCols = dataset.select((datasetCols ::: datasetInputCols):_*)
    addedCols.na.fill(replacementValues)
  }

  @DeveloperApi
  override def transformSchema(schema: StructType): StructType =
    CleanMissingData.validateAndTransformSchema(schema, inputCols, outputCols)
}

object CleanMissingDataModel extends MLReadable[CleanMissingDataModel] {

  private val replacementValuesPart = "replacementValues"
  private val inputColsPart = "inputCols"
  private val outputColsPart = "outputCols"
  private val dataPart = "data"

  override def read: MLReader[CleanMissingDataModel] = new CleanMissingDataModelReader

  override def load(path: String): CleanMissingDataModel = super.load(path)

  /** [[MLWriter]] instance for [[CleanMissingDataModel]] */
  private[CleanMissingDataModel]
  class CleanMissingDataModelWriter(val uid: String,
                                    val replacementValues: Map[String, Any],
                                    val inputCols: Array[String],
                                    val outputCols: Array[String])
    extends MLWriter {
    private case class Data(uid: String)

    override protected def saveImpl(path: String): Unit = {
      val overwrite = this.shouldOverwrite
      val qualPath = PipelineUtilities.makeQualifiedPath(sc, path)
      // Required in order to allow this to be part of an ML pipeline
      PipelineUtilities.saveMetadata(uid,
        CleanMissingDataModel.getClass.getName.replace("$", ""),
        new Path(path, "metadata").toString,
        sc,
        overwrite)

      // save the replacement values
      ObjectUtilities.writeObject(replacementValues, qualPath, replacementValuesPart, sc, overwrite)

      // save the input cols and output cols
      ObjectUtilities.writeObject(inputCols, qualPath, inputColsPart, sc, overwrite)
      ObjectUtilities.writeObject(outputCols, qualPath, outputColsPart, sc, overwrite)

      // save model data
      val data = Data(uid)
      val dataPath = new Path(qualPath, dataPart).toString
      val saveMode =
        if (overwrite) SaveMode.Overwrite
        else SaveMode.ErrorIfExists
      sparkSession.createDataFrame(Seq(data)).repartition(1).write.mode(saveMode).parquet(dataPath)
    }
  }

  private class CleanMissingDataModelReader
    extends MLReader[CleanMissingDataModel] {

    override def load(path: String): CleanMissingDataModel = {
      val qualPath = PipelineUtilities.makeQualifiedPath(sc, path)
      // load the uid
      val dataPath = new Path(qualPath, dataPart).toString
      val data = sparkSession.read.format("parquet").load(dataPath)
      val Row(uid: String) = data.select("uid").head()

      // get the replacement values
      val replacementValues = ObjectUtilities.loadObject[Map[String, Any]](qualPath, replacementValuesPart, sc)
      // get the input and output cols
      val inputCols = ObjectUtilities.loadObject[Array[String]](qualPath, inputColsPart, sc)
      val outputCols = ObjectUtilities.loadObject[Array[String]](qualPath, outputColsPart, sc)

      new CleanMissingDataModel(uid, replacementValues, inputCols, outputCols)
    }
  }

}
