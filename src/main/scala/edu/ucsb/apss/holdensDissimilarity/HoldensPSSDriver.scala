package edu.ucsb.apss.holdensDissimilarity

import edu.ucsb.apss.InvertedIndex.InvertedIndex._
import edu.ucsb.apss.InvertedIndex.InvertedIndex
import edu.ucsb.apss.util.ExternalFileManager

import edu.ucsb.apss.util.PartitionUtil.VectorWithNorms

import edu.ucsb.apss.partitioning.{PartitionHasher, HoldensPartitioner}
import org.apache.spark._
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.rdd.RDD
import org.apache.log4j.Logger
import scala.collection.mutable.{HashMap => MMap}


import scala.collection.mutable.{ListBuffer, ArrayBuffer}


import scala.collection.mutable.{ListBuffer, ArrayBuffer}


/**
  * Created by dimberman on 1/3/16.
  */


case class DebugVal(key:(Int,Int), time:Double, numPairs:Int, numBuckets:Int)

object DebugAcummulatorParam extends AccumulableParam[ArrayBuffer[DebugVal],DebugVal] {
    def zero(value:ArrayBuffer[DebugVal]) : ArrayBuffer[DebugVal] = value
    def addInPlace(s1:ArrayBuffer[DebugVal], s2:ArrayBuffer[DebugVal]):ArrayBuffer[DebugVal] = {
        s1 ++= s2
        s1
    }
    def addAccumulator(s1:ArrayBuffer[DebugVal], d:DebugVal) = {
        s1 += d
        s1
    }
}



object LineAcummulatorParam extends AccumulatorParam[String] {
    def zero(value:String) : String = value
    def addInPlace(s1:String, s2:String):String = s1 + "\n" + s2
}


class BucketCompartmentalizer(cSize: Int) extends Serializable {

    def aggregateBucket(input: RDD[((Int, Int), VectorWithNorms)]) = {
        input.aggregateByKey(ArrayBuffer[ArrayBuffer[VectorWithNorms]]())(addVector, mergeB)
    }


    def addVector(a: ArrayBuffer[ArrayBuffer[VectorWithNorms]], vec: VectorWithNorms): ArrayBuffer[ArrayBuffer[VectorWithNorms]] = {
        var merged = false
        var i = 0
        while (i < a.size && !merged) {
            if (a(i).size < cSize) {
                merged = true
                a(i) += vec
            }
            i += 1
        }
        if (!merged) {
            val n = ArrayBuffer[VectorWithNorms](vec)
            a += n
        }
        a
    }

    def mergeB(a: ArrayBuffer[ArrayBuffer[VectorWithNorms]], b: ArrayBuffer[ArrayBuffer[VectorWithNorms]]): ArrayBuffer[ArrayBuffer[VectorWithNorms]] = {
        a ++= b
        a
    }

}

class HoldensPSSDriver {

    import edu.ucsb.apss.util.PartitionUtil._
    import HoldensPartitioner._

    var debugPSS = true
    val partitioner = HoldensPartitioner
    //    @transient lazy val log = Logger.getLogger(getClass.getName)
    val log = Logger.getLogger(getClass.getName)
    var bucketSizes: List[Int] = _
    var tot = 0L
    var sParReduction = 0L
    var dParReduction = 0L
    var bucketizedVectorSizeMap:Map[(Int,Int), Long] = _
    var appId: String = _
    var sPar = 0L
    var dPar = 0L
    var numVectors = 0L


    type BucketizedVector = ((Int, Int), VectorWithNorms)

    def bucketizeVectors(sc: SparkContext, vectors: RDD[SparseVector], numBuckets: Int, threshold: Double): RDD[((Int, Int), VectorWithNorms)] = {
        //        sContext = sc
        val count = vectors.count
        numVectors = count
        val normalizedVectors = vectors.repartition(numBuckets).map(normalizeVector)
        val indexednVecs = recordIndex(normalizedVectors)
        val l1partitionedVectors = partitionByL1Sort(indexednVecs, numBuckets, count).mapValues(extractUsefulInfo)

        if (debugPSS) bucketSizes = l1partitionedVectors.countByKey().toList.sortBy(_._1).map(_._2.toInt)

        val bucketLeaders = determineBucketLeaders(l1partitionedVectors)
        partitioner.tieVectorsToHighestBuckets(l1partitionedVectors, bucketLeaders, threshold, sc)
    }


    def run(sc: SparkContext, vectors: RDD[SparseVector], numBuckets: Int, threshold: Double, debug: Boolean = true) = {
        debugPSS = debug
        val bucketizedVectors: RDD[BucketizedVector] = bucketizeVectors(sc, vectors, numBuckets, threshold).repartition(30)

        bucketizedVectorSizeMap = bucketizedVectors.countByKey().toMap.withDefault(_=> -1)
        val manager = new ExternalFileManager

//        if (debugPSS) staticPartitioningBreakdown(bucketizedVectors, threshold, numBuckets)

        val c = new BucketCompartmentalizer(1000)

        manager.writePartitionsToFile(bucketizedVectors)


        val numParts = (numBuckets * (numBuckets + 1)) / 2
        val invertedIndexes = generateInvertedIndexes(bucketizedVectors, Map(), numParts)
        calculateCosineSimilarityByPullingFromFile(invertedIndexes, threshold, numBuckets)
    }


    def staticPartitioningBreakdown(bucketizedVectors: RDD[BucketizedVector], threshold: Double, numBuckets: Int): Unit = {
        //        var skipped: Long = 0

        val bv = bucketizedVectors.collect()
        val breakdown = bucketizedVectors.countByKey()
        //        breakdown.foreach { case (k, v) => skipped += k._2 * bucketSize * v }
        bucketSizes.foreach(b => println("bucket size: " + b))
        log.info("breakdown: *******************************************************")
        log.info("breakdown: *******************************************************")
        log.info(s"breakdown: ******** computing PSS with a threshold of $threshold ********")

        val numVecs = breakdown.values.sum
        log.info("breakdown: number of vectors: " + numVecs)
        log.info("breakdown: total number of pairs: " + (numVecs * numVecs) / 2)
        val skippedPairs = breakdown.toList.map {
            case ((b, t), v) =>
                if (b == t) {
                    ((b, t), 0)
                }
                else {
                    var nvec = 0
                    for (i <- 0 to t) nvec += v.toInt * bucketSizes(i)
                    ((b, t), nvec)
                }
        }.map(a => a._2).sum
        println(numBuckets)
        val keptPairs = breakdown.toList.map {
            case ((b, t), v) =>
                var nvec = 0
                for (i <- t + 1 to b) nvec += v.toInt * bucketSizes(i)
                ((b, t), nvec)
        }.map(a => a._2).sum
        log.info("breakdown: *******************************************************")

        log.info(s"breakdown: static partitioning:")

        log.info(s"breakdown: skipped pairs: $skippedPairs")
        log.info(s"breakdown: kept pairs: $keptPairs")
        log.info(s"breakdown: ${(numVecs * numVecs) / 2 - keptPairs - skippedPairs + 10000} unnacounted for")
        sPar = skippedPairs

        val total = skippedPairs + keptPairs

        tot = total
        log.info("breakdown: *******************************************************")
        log.info("breakdown: bucket breakdown:")

        breakdown.toList.map { case ((b, t), v) =>
            var nvec = 0
            for (i <- 0 to b) nvec += bucketSizes(i)
            if (b == t) {
                ((b, t), (0, nvec))
            }
            else {
                var nvecSkipped = 0
                for (i <- 0 to t) nvecSkipped += bucketSizes(i)
                ((b, t), (nvecSkipped, nvec))

            }
        }.sortBy(_._1).foreach {
            case (k, (v, n)) =>
                log.info(s"breakdown: $k: $n vectors. $v skipped")
        }
        log.info("breakdown: ")
        log.info("breakdown: ")

    }


    def pullKey(a: (Int, Int)) = (a._1 * (a._1 + 1)) / 2 + a._2


    def calculateCosineSimilarityByPullingFromFile(invertedIndexes: RDD[((Int, Int), InvertedIndex)], threshold: Double, numBuckets: Int): RDD[(Long, Long, Double)] = {
        //        log.info(s"num partitions: ${partitionedTasks.partitions.length}")
        val skipped: Accumulator[Long] = invertedIndexes.context.accumulator[Long](0)
        val reduced: Accumulator[Long] = invertedIndexes.context.accumulator[Long](0)
        val all: Accumulator[Long] = invertedIndexes.context.accumulator[Long](0)
        val indx: Accumulator[Long] = invertedIndexes.context.accumulator[Long](0)

        invertedIndexes.count()

        val buckets = invertedIndexes.filter(_._2.indices.nonEmpty).keys.collect()
        val neededVecs = buckets.sortBy(a => a)


        val manager = new ExternalFileManager

        val pairs = buckets.map { case (b, t) => ((b, t), manager.assignByBucket(b, t, numBuckets,neededVecs)) }
        //
//        log.info("breakdown: pre-filtering")
//
//
//        pairs.foreach { case (k, v) => log.info(s"breakdown: $k: ${v.mkString(",")}") }
//
//        log.info("breakdown: post-filtering")
//
//        filteredPairs.foreach { case (k, v) => log.info(s"breakdown: $k: ${v.mkString(",")}") }
//
//
//
//        log.info(s"breakdown: needed vecs: ${neededVecs.toList.sorted.mkString(",")}")
//        log.info("breakdown: pre-filtering")
//
//
//        pairs.foreach { case (k, v) => log.info(s"breakdown: $k: ${v.size}") }
//
//        log.info("breakdown: post-filtering")
//
//        filteredPairs.foreach { case (k, v) => log.info(s"breakdown: $k: ${v.size}") }

        val sc = invertedIndexes.context
        val BVConf = sc.broadcast(new SerializableWritable(sc.hadoopConfiguration))


        val id = invertedIndexes.context.applicationId
        val buckAccum =  invertedIndexes.context.accumulator("","debug info")(LineAcummulatorParam)
        val driverAccum =  invertedIndexes.context.accumulable(ArrayBuffer[DebugVal](),"debug info")(DebugAcummulatorParam)


//        invertedIndexes.foreachPartition(i => {
//          val handled = i.map(_._1).map((new PartitionHasher).partitionUnHash).toList
//            buckAccum += s"breakdown: ${handled.mkString(",")} are all in the same partition"
//
//        }
//        )


        log.info(buckAccum.value)

        val similarities: RDD[Similarity] = invertedIndexes.flatMap {
            case ((key, inv)) =>
                val manager = new ExternalFileManager
                //                val get = List().toIterator

                var numVecPair = 0
                val start = System.currentTimeMillis()
                val get = manager.assignByBucket(inv.bucket, inv.tl, numBuckets, neededVecs)
                val filtered = get.filter(neededVecs.contains)
                val numBuc = filtered.size
//                println(s"breakdown: assignment ${get.mkString(",")}")

//                val answer = new ArrayBuffer[Similarity]()
                val answer = new BoundedPriorityQueue[Similarity](1000)

                filtered.foreach {
                    case (key) =>
                        val vectors = manager.readPartition(key, id, BVConf, org.apache.spark.TaskContext.get()).toList.zipWithIndex.filter(_._2 % inv.numSplits == inv.splitIndex).map(_._1)
                        val indexMap = InvertedIndex.extractIndexMap(inv)
                        val score = new Array[Double](indexMap.size)
                        val (bucket, invertedIndex) = (inv.bucket, inv.indices)
                        vectors.foreach {
                            case v_j =>
                                var r_j = v_j.l1
                                val vec = v_j.vector
                                //                                val mutualVectorFeatures = vec.indices.zipWithIndex.filter(b => invertedIndex.contains(b._1))
                                var j = 0
                                vec.indices.foreach {
                                    case (featureIndex) =>
                                        if (invertedIndex.contains(featureIndex)) {
                                            val weight_j = vec.values(j)
                                            invertedIndex(featureIndex).foreach {
                                                case (featurePair) => {
                                                    val (ind_i, weight_i) = (featurePair.id, featurePair.weight)
                                                    val l = indexMap(ind_i)
                                                    //                                                    if (!((score(l) + inv.maxMap(ind_i) * r_j) < threshold))
                                                    score(l) += weight_i * weight_j
                                                }
                                                //                                                    r_j -= weight_j
                                            }
                                        }
                                        j += 1
                                }

                                indexMap.keys.foreach {
                                    ind_i =>
                                        val l = indexMap(ind_i)
                                        val ind_j = v_j.index
                                        if (score(l) > threshold && ind_i != ind_j) {
                                            val c = Similarity(ind_i, ind_j.toLong, score(l))
                                            answer += c
                                            all += 1
                                            reduced += 1
                                            numVecPair +=1

                                        }
                                        else {
                                            //                                            log.info(s"skipped vector pair ($ind_i, $ind_j) with score ${score(l)}")
                                            skipped += 1
                                            all += 1
                                            numVecPair +=1

                                        }
                                }
                                for (l <- score.indices) {
                                    score(l) = 0
                                }
                        }
                }
                val time = (System.currentTimeMillis() - start).toDouble/1000
//                driverAccum += s"breakdown: partition ${(inv.bucket,inv.tl)} took $time seconds to calculate $numVecPair pairs from $numBuc buckets"
                driverAccum += DebugVal((inv.bucket, inv.tl), time, numVecPair, numBuc)
                answer
        }.persist()
        similarities.count()


        log.info(driverAccum.value.sortBy(_.numPairs).map(d => s"breakdown: partition ${d.key} took ${d.time} seconds to calculate ${d.numPairs} pairs from ${d.numBuckets} buckets").mkString("\n"))

        log.info("breakdown: *******************************************************")
        log.info("breakdown: dynamic partitioning:")

        log.info(s"breakdown: ${all.value} pairs considered after duplicate pair removal")

        sParReduction = numVectors * numVectors / 2 - all.value
        dParReduction = skipped.value
        log.info("breakdown: " + skipped.value + " vector pairs skipped due to dynamic partitioning")
        dPar = all.value
        log.info("breakdown: " + reduced.value + " vector pairs returned after dynamic partitioning")
        //        log.info("breakdown: index vecs " + indx.value)
        log.info("breakdown: " + (all.value - skipped.value - reduced.value) + " values unaccounted for")

        log.info("breakdown:staticPairRemoval," + sParReduction)
        log.info("breakdown:static%reduction," + sParReduction.toDouble/(numVectors*numVectors/2))
        manager.cleanup(sc.applicationId, BVConf)
        similarities.map(s => (s.i, s.j, s.similarity))
    }

}





