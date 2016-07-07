package com.highperformancespark.examples.structuredstreaming

import org.apache.spark.sql.streaming.OutputMode

import org.apache.spark.ml.util.Identifiable
import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.ml.param._

import MLUtils.axpy

trait StreamingNaiveBayesParams extends Params {
  /**
   * The smoothing parameter.
   * (default = 1.0).
   * @group param
   */
  final val smoothing: DoubleParam = new DoubleParam(this, "smoothing", "The smoothing parameter.",
    ParamValidators.gtEq(0))

  /** @group getParam */
  final def getSmoothing: Double = getOrDefault(smoothing)
}

class StreamingNaiveBayesModel(
    val uid: String,
    val pi: Vector,
    val theta: Matrix) {
  // TODO: it would be nice if we could inherit from NaiveBayesModel
}

class StreamingNaiveBayes (
    override val uid: String) extends StreamingNaiveBayesParams with Serializable {

  def this() = this(Identifiable.randomUID("snb"))

  /**
   * HashMap with keys being class labels, values are
   * (numInstancesWithClassLabel, cumulativeTermFrequenciesForClass)
   *
   * Note: this is stored as a mutable map so that we don't need to know the number of outcome
   * classes beforehand. Alternately, the class could take `numLabels` as a parameter and store
   * this as an array, which is how it is done in the batch algo.
   */
  protected val countsByClass = new collection.mutable.HashMap[Double, (Long, DenseVector)]

  /**
   * Set the smoothing parameter.
   * Default is 1.0.
   * @group setParam
   */
  def setSmoothing(value: Double): this.type = set(smoothing, value)
  setDefault(smoothing -> 1.0)

  def hasModel = countsByClass.nonEmpty

  private var isModelUpdated = true

  /*
  /**
   * Train the model on a streaming DF using evil tricks
   */
  def evilTrain(df: DataFrame): Unit = {
    val sink = new ForeachDatasetSink({df: DataFrame => update(df)})
    val sparkSession = df.sparkSession
    val evilStreamingQueryManager = EvilStreamingQueryManager(sparkSession.streams)
    evilStreamingQueryManager.startQuery(
      "snb-train",
      None,
      df,
      sink,
      OutputMode.Append(),
      useTempCheckpointLocation = true,
      ProcessingTime(0L)
    )
  } */

  /**
   * Update the class counts with a new chunk of labeled point data.
   *
   * @param df Dataframe to add
   */
  def update(df: DataFrame): Unit = {
    isModelUpdated = false
    import df.sparkSession.implicits._
    val data = df.as[LabeledPoint].rdd
    val newCountsByClass = add(data)
    merge(newCountsByClass)
  }


  /**
   * Get the log class probabilities and prior probabilities from the aggregated counts.
   */
  def getModel: StreamingNaiveBayesModel = {
    val lambda = getSmoothing
    val numLabels = countsByClass.size
    var numDocuments = 0L
    countsByClass.foreach { case (_, (n, _)) =>
      numDocuments += n
    }
    val numFeatures = countsByClass.head match { case (_, (_, v)) => v.size }

    val labels = new Array[Double](numLabels)
    val pi = new Array[Double](numLabels)
    val theta = Array.fill(numLabels)(new Array[Double](numFeatures))

    val piLogDenom = math.log(numDocuments + numLabels * lambda)
    var i = 0
    countsByClass.toArray.sortBy(_._1).foreach { case (label, (n, sumTermFreqs)) =>
      labels(i) = label
      pi(i) = math.log(n + lambda) - piLogDenom
      val thetaLogDenom = math.log(sumTermFreqs.values.sum + numFeatures * lambda)
      var j = 0
      while (j < numFeatures) {
        theta(i)(j) = math.log(sumTermFreqs(j) + lambda) - thetaLogDenom
        j += 1
      }
      i += 1
    }
    new StreamingNaiveBayesModel(Identifiable.randomUID("snb"),
      Vectors.dense(pi),
      new DenseMatrix(labels.length, theta(0).length, theta.flatten, true))
  }

  /**
   * Combine the current class counts with aggregated class counts from a new chunk of data.
   */
  def merge(update: Array[(Double, (Long, DenseVector))]): Unit = {
    update.foreach { case (label, (numDocs, termCounts)) =>
      countsByClass.get(label) match {
        case Some((n, c)) =>
          axpy(1.0, termCounts, c)
          countsByClass(label) = (n + numDocs, c)
        case None =>
          // new label encountered
          countsByClass += (label -> (numDocs, termCounts))
      }
    }
  }

  /**
   * Get class counts for a new chunk of data.
   */
  def add(data: RDD[LabeledPoint]): Array[(Double, (Long, DenseVector))] = {
    data.map(lp => (lp.label, lp.features)).combineByKey[(Long, DenseVector)](
      createCombiner = (v: Vector) => {
        (1L, v.copy.toDense)
      },
      mergeValue = (c: (Long, DenseVector), v: Vector) => {
        // TODO: deal with sparse
        axpy(1.0, v.toDense, c._2)
        (c._1 + 1L, c._2)
      },
      mergeCombiners = (c1: (Long, DenseVector), c2: (Long, DenseVector)) => {
        axpy(1.0, c2._2, c1._2)
        (c1._1 + c2._1, c1._2)
      }
    ).collect()
  }

  override def copy(extra: ParamMap): StreamingNaiveBayes = defaultCopy(extra)
}


object SimpleStreamingNaiveBayes {
  val model = new StreamingNaiveBayes()
}

class StreamingNaiveBayesSinkprovider extends ForeachDatasetSinkProvider {
  override def func(df: DataFrame) {
    val spark = df.sparkSession
    import spark.implicits._
    SimpleStreamingNaiveBayes.model.update(df)
  }
}
