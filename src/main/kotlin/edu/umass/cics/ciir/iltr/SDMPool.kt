package edu.umass.cics.ciir.iltr

import edu.umass.cics.ciir.chai.safeDiv
import edu.umass.cics.ciir.chai.smartPrint
import edu.umass.cics.ciir.irene.QueryLikelihood
import edu.umass.cics.ciir.irene.SequentialDependenceModel
import edu.umass.cics.ciir.sprf.*
import org.lemurproject.galago.core.eval.QueryJudgments
import org.lemurproject.galago.utility.Parameters
import java.io.File

/**
 * @author jfoley
 */
fun main(args: Array<String>) {
    val argp = Parameters.parseArgs(args)
    val dsName = argp.get("dataset", "gov2")
    val dataset = DataPaths.get(dsName)
    val qrels = dataset.getQueryJudgments()
    val ms = NamedMeasures()
    val depth = argp.get("depth", 200)

    File("$dsName.irene.pool.jsonl.gz").smartPrint { output ->
        dataset.getIreneIndex().use { index ->
            index.env.estimateStats = "min"
            dataset.getTitleQueries().entries.stream().map { (qid, qtext) ->
                val queryJudgments = qrels[qid] ?: QueryJudgments(qid, emptyMap())
                val relDocs = queryJudgments.filterValues { it > 0 }.keys.filterNotNullTo(HashSet())
                val qterms = index.tokenize(qtext)

                val poolingQueries = mapOf(
                        "sdm" to SequentialDependenceModel(qterms, stopwords = inqueryStop),
                        "ql" to QueryLikelihood(qterms)
                        //"bm25" to UnigramRetrievalModel(qterms, scorer = {BM25Expr(it)})
                )

                val results = index.pool(poolingQueries, depth)
                val inPool = results.values.flatMapTo(HashSet()) { it.scoreDocs.map { it.doc } }

                val rawDocs = inPool.associate { doc ->
                    val ldoc = index.document(doc)!!
                    val fields = pmake {}
                    ldoc.fields.forEach { field ->
                        val name = field.name()!!
                        fields.putIfNotNull(name, field.stringValue())
                        fields.putIfNotNull(name, field.numericValue())
                    }
                    Pair(doc, fields)
                }
                val docPs = inPool.map { id ->
                    val fields = rawDocs[id]!!
                    val docName = fields.getString(index.idFieldName)!!
                    pmake {
                        set("id", docName)
                        set("fields", fields)
                    }
                }

                val qjson = pmake {
                    set("qid", qid)
                    set("docs", docPs)
                    set("qtext", qtext)
                    set("qterms", qterms)
                }

                val relFound = docPs.count { relDocs.contains(it.getStr("id")) }
                println("qid:$qid, R:${safeDiv(relFound, relDocs.size)} R:$relFound/${relDocs.size}")
                qjson
            }.sequential().forEach { qjson ->
                output.println(qjson);
            }
        } // retr
    } // output
    println(ms.means())
}
