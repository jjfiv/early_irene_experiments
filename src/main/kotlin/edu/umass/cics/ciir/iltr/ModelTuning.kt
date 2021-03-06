package edu.umass.cics.ciir.iltr

import edu.umass.cics.ciir.irene.utils.mean
import edu.umass.cics.ciir.irene.utils.push
import edu.umass.cics.ciir.irene.galago.RRGalagoEnv
import edu.umass.cics.ciir.irene.lang.*
import edu.umass.cics.ciir.irene.ltr.RREnv
import edu.umass.cics.ciir.sprf.DataPaths
import edu.umass.cics.ciir.irene.galago.getEvaluators
import org.lemurproject.galago.utility.Parameters

/**
 *
 * @author jfoley.
 */
interface HyperParam {
    override fun hashCode(): Int
    override fun equals(other: Any?): Boolean
    override fun toString(): String
}

inline fun <P : HyperParam> kCrossFoldValidate(dsName: String, showTest: Boolean = false, kSplits: Int = 5, tune: String = "ap", crossinline compute: (RREnv, LTRQuery)->Map<P, QExpr>) {
    val dataset = DataPaths.get(dsName)
    val tuningMeasure = getEvaluators(listOf(tune))[tune]!!
    val qrels = dataset.getQueryJudgments()

    // hyperparam -> tuningMeasure
    val hpResults = HashMap<P, HashMap<String, Double>>()

    val splitToQids = HashMap<Int, MutableList<String>>()
    dataset.title_qs.keys.sorted().forEachIndexed { i, qid ->
        val splitId = i % kSplits
        splitToQids.push(splitId, qid)
    }

    dataset.getIndex().use { index ->
        val env = RRGalagoEnv(index)
        forEachQuery(dsName) { q ->
            println("${q.qid} ${q.qterms}")
            val queryJudgments = qrels[q.qid]!!
            compute(env, q).mapValues { (hp, expr) ->
                val ranked = q.toQResults(env, expr)
                val score = tuningMeasure.evaluate(ranked, queryJudgments)
                //println("\t\t$score . $hp")
                hpResults.computeIfAbsent(hp,{ HashMap() }).put(q.qid, score)
            }
            println("\tComputed gain for ${hpResults.size} settings.")
        }
    }

    val scoreMean = splitToQids.map { (splitId, test) ->
        val train = splitToQids.filterKeys { it != splitId }.values.flatten()

        val (bestHP, bestTrainScore) = hpResults.map { (hp, items) ->
            val trainMeasure = train.map { items[it]!! }.mean()
            Pair(hp, trainMeasure)
        }.sortedByDescending { it.second }.first()

        val bestItems = hpResults[bestHP]!!
        val testScore = test.map { bestItems[it]!! }.mean()

        if (showTest) {
            println("Split $splitId: Train: $bestTrainScore for $bestHP, Test: $testScore")
            testScore
        } else {
            println("Split $splitId: Train: $bestTrainScore for $bestHP")
            bestTrainScore
        }
    }.mean()

    if (showTest) {
        println("Overall Test Mean: $scoreMean")
    } else {
        println("Overall Train Mean: $scoreMean")
    }
}

data class BM25HyperParam(val b: Double = 0.75, val k: Double = 1.2) : HyperParam { }
object BM25Tuning {
    @JvmStatic fun main(args: Array<String>) {
        val argp = Parameters.parseArgs(args)
        val dsName = argp.get("dataset", "robust")
        val field = argp.get("field", "document")

        val ks = arrayListOf(0.0, .25, .5, .75, 1.0)
        val bs = ks.toList()

        kCrossFoldValidate(dsName) { env, q ->
            val out = HashMap<BM25HyperParam, QExpr>()
            ks.forEach { k ->
                bs.forEach { b ->
                    val expr = MeanExpr(q.qterms.map { BM25Expr(TextExpr(it, field), b, k) })
                    out[BM25HyperParam(b, k)] = expr
                }
            }
            out
        }
    }
}

data class QLHyperParam(val mu: Double = 1500.0): HyperParam
object TuneQL {
    @JvmStatic fun main(args: Array<String>) {
        val argp = Parameters.parseArgs(args)
        val dsName = argp.get("dataset", "robust")
        val field = argp.get("field", "document")

        val mus = arrayListOf(750,1000,1250,1500).map { it.toDouble() }

        kCrossFoldValidate(dsName) { env, q ->
            val out = HashMap<QLHyperParam, QExpr>()
            mus.forEach { mu ->
                val expr = MeanExpr(q.qterms.map { DirQLExpr(TextExpr(it, field), mu) })
                out[QLHyperParam(mu)] = expr
            }
            out
        }
    }
}