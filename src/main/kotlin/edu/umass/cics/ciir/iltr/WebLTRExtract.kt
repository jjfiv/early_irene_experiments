package edu.umass.cics.ciir.iltr

import edu.umass.cics.ciir.chai.smartDoLines
import edu.umass.cics.ciir.chai.smartPrint
import edu.umass.cics.ciir.irene.FullDependenceModel
import edu.umass.cics.ciir.irene.GenericTokenizer
import edu.umass.cics.ciir.irene.QueryLikelihood
import edu.umass.cics.ciir.irene.SequentialDependenceModel
import edu.umass.cics.ciir.sprf.*
import org.lemurproject.galago.core.eval.QueryJudgments
import org.lemurproject.galago.utility.Parameters
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author jfoley
 */
fun forEachSDMPoolQuery(tokenizer: GenericTokenizer, dsName: String, doFn: (LTRQuery) -> Unit) {
    File("$dsName.irene-sdm.qlpool.jsonl.gz").smartDoLines { line ->
        val qjson = Parameters.parseStringOrDie(line)
        val qid = qjson.getStr("qid")
        val qtext = qjson.getStr("qtext")
        val qterms = qjson.getAsList("qterms", String::class.java)

        val docs = qjson.getAsList("docs", Parameters::class.java).map { LTRDocOfWeb(tokenizer, it) }

        doFn(LTRQuery(qid, qtext, qterms, docs))
    }
}

val MandatoryFields = arrayListOf<String>("title", "body", "document")
fun LTRDocOfWeb(tokenizer: GenericTokenizer, p: Parameters): LTRDoc {
    val fjson = p.getMap("fields")

    val features = HashMap<String, Double>()
    val fields = HashMap<String, ILTRDocField>()

    fjson.keys.forEach { key ->
        if (fjson.isString(key)) {
            val fieldText = fjson.getStr(key)
            fields.put(key, LTRDocField(key, fieldText, tokenizer))
        } else if(fjson.isDouble(key)) {
            features.put("double-field-$key", fjson.getDouble(key))
        } else if(fjson.isLong(key)) {
            features.put("long-field-$key", fjson.getLong(key).toDouble())
        } else {
            println("Warning: Can't handle field: $key=${fjson[key]}")
        }
    }

    for (f in MandatoryFields) {
        if (!fields.containsKey(f)) {
            fields[f] = LTREmptyDocField(f)
        }
    }

    val name = p.getStr("id")
    val rank = p.getInt("rank")
    features["title-ql-prior"] = p.getDouble("title-ql-prior")
    features["title-ql"] = p.getDouble("title-ql")

    return LTRDoc(name, features, rank, fields)
}

fun main(args: Array<String>) {
    val argp = Parameters.parseArgs(args)
    val dsName = argp.get("dataset", "clue09")
    val dataset = DataPaths.get(dsName)
    val evals = getEvaluators(listOf("ap", "ndcg"))
    val ms = NamedMeasures()
    val qrels = dataset.getQueryJudgments()
    val fbTerms = 100
    val qid = argp.get("qid")?.toString()
    val qidBit = if (qid == null) "" else ".$qid"

    File("l2rf/$dsName$qidBit.features.jsonl.gz").smartPrint { out ->
        dataset.getIreneIndex().use { index ->
            val env = index.getRREnv()
            forEachSDMPoolQuery(index.tokenizer, dsName) { q ->
                if (qid != null && qid != q.qid) {
                    // skip all but qid if specified.
                    return@forEachSDMPoolQuery
                }
                val queryJudgments = qrels[q.qid] ?: QueryJudgments(q.qid, emptyMap())
                val feature_exprs = HashMap<String, RRExpr>()

                arrayListOf("title", "body", "document").forEach { fieldName ->
                    val qterms = index.tokenize(q.qtext, fieldName)
                    println("${q.qid} $fieldName: $qterms")
                    q.docs.forEach { doc ->
                        doc.features["$fieldName:qlen"] = qterms.size.toDouble()
                        doc.features["$fieldName:qstop"] = qterms.count { inqueryStop.contains(it) }.toDouble()
                    }
                    feature_exprs.putAll(hashMapOf<String, RRExpr>(
                            Pair("bm25", env.bm25(qterms, fieldName)),
                            Pair("LM-dir", QueryLikelihood(qterms, fieldName).toRRExpr(env)),
                            Pair("LM-abs", env.mean(qterms.map { RRAbsoluteDiscounting(env, it, fieldName) })),
                            Pair("docinfo", RRDocInfoQuotient(env, fieldName)),
                            Pair("fdm-stop", FullDependenceModel(qterms, field = fieldName, stopwords = inqueryStop).toRRExpr(env)),
                            Pair("sdm-stop", SequentialDependenceModel(qterms, field = fieldName, stopwords = inqueryStop).toRRExpr(env)),
                            Pair("avgwl", RRAvgWordLength(env, field = fieldName)),
                            Pair("meantp", env.mean(qterms.map { RRTermPosition(env, it, fieldName) })),
                            Pair("jaccard-stop", RRJaccardSimilarity(env, inqueryStop, field = fieldName)),
                            Pair("length", RRDocLength(env, field = fieldName))).mapKeys { (k, _) -> "$fieldName:$k" }
                    )
                }

                arrayListOf<Int>(5, 10, 25).forEach { fbDocs ->
                    val rm = env.computeRelevanceModel(q.docs, "title-ql-prior", fbDocs)
                    arrayListOf("title", "body", "document").forEach { fieldName ->
                        val wt = rm.toTerms(fbTerms)
                        val rmeExpr = rm.toQExpr(fbTerms, targetField = fieldName).toRRExpr(env)
                        feature_exprs.put("$fieldName:rm1-k$fbDocs", rmeExpr)
                        feature_exprs.put("$fieldName:jaccard-rm3-k$fbDocs", RRJaccardSimilarity(env, wt.map { it.term }.toSet(), field = fieldName))
                    }
                }


                val skippedFeatures = AtomicInteger(0)
                q.docs.parallelStream().forEach { doc ->
                    feature_exprs.forEach { fname, fexpr ->
                        val value = fexpr.eval(doc)
                        if (value.isInfinite() || value.isNaN()) {
                            skippedFeatures.incrementAndGet()
                        } else {
                            doc.features.put(fname, value)
                        }
                    }
                }
                if (skippedFeatures.get() > 0) {
                    println("Skipped NaN or Infinite features: ${skippedFeatures.get()}")
                }

                arrayListOf<String>("title:rm1-k10", "body:rm1-k10", "title:sdm-stop", "body:sdm-stop").forEach { method ->
                    evals.forEach { measure, evalfn ->
                        val score = try {
                            val results = q.toQResults(method)
                            if (results.isEmpty()) {
                                0.0
                            } else {
                                evalfn.evaluate(results, queryJudgments)
                            }
                        } catch (npe: NullPointerException) {
                            System.err.println("NULL in eval...")
                            npe.printStackTrace()
                            -Double.MAX_VALUE
                        }
                        ms.push("$measure.$method", score)
                    }
                }

                q.toJSONFeatures(queryJudgments).forEach { out.println(it) }
                println(ms.means())
            }
        }
    }
}