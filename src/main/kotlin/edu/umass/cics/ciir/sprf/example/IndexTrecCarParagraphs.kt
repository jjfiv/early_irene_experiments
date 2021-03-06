package edu.umass.cics.ciir.sprf.example

import edu.umass.cics.ciir.irene.utils.CountingDebouncer
import edu.umass.cics.ciir.irene.utils.smartDoLines
import edu.umass.cics.ciir.irene.utils.smartPrint
import edu.umass.cics.ciir.iltr.pagerank.SpacesRegex
import edu.umass.cics.ciir.irene.IndexParams
import edu.umass.cics.ciir.irene.IreneIndex
import edu.umass.cics.ciir.irene.IreneIndexer
import edu.umass.cics.ciir.irene.lang.SequentialDependenceModel
import edu.umass.cics.ciir.irene.galago.toQueryResults
import edu.umass.cics.ciir.irene.galago.NamedMeasures
import edu.umass.cics.ciir.irene.galago.getEvaluators
import edu.umass.cics.ciir.irene.galago.inqueryStop
import edu.umass.cics.ciir.irene.galago.pmake
import edu.unh.cs.treccar.Data
import edu.unh.cs.treccar.read_data.DeserializeData
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.lemurproject.galago.core.eval.QueryJudgments
import org.lemurproject.galago.core.eval.QuerySetJudgments
import org.lemurproject.galago.utility.Parameters
import org.lemurproject.galago.utility.StreamCreator
import java.io.File
import kotlin.streams.asStream

fun getTrecCarIndexParams(path: File) = IndexParams().apply {
    withPath(path)
    defaultField = "text"
    withAnalyzer("links", WhitespaceAnalyzer())
}

/**
 *
 * @author jfoley.
 */
fun main(args: Array<String>) {
    val argp = Parameters.parseArgs(args)
    val paragraphsInput = File(argp.get("input", "/mnt/scratch/jfoley/trec-car/paragraphcorpus/paragraphcorpus.cbor"))
    val indexPath = File(argp.get("output", "/mnt/scratch/jfoley/trec-car/pcorpus.irene2"))

    // Seven million paragraphs (as described in paper)
    // much more in actual 16GB dump
    val total = argp.get("total", 29_678_367L)
    val msg = CountingDebouncer(total)

    IreneIndexer(getTrecCarIndexParams(indexPath).apply { create() }).use { writer ->
        StreamCreator.openInputStream(paragraphsInput).use { input ->
            DeserializeData.iterParagraphs(input).asSequence().asStream().parallel().forEach { paragraph: Data.Paragraph ->
                val id = paragraph.paraId
                val text = paragraph.textOnly
                val links = paragraph.entitiesOnly

                writer.doc {
                    setId(id)
                    setEfficientTextField("text", text)
                    setEfficientTextField("links", links.joinToString(separator="\t"))
                }

                msg.incr()?.let { upd ->
                    println("$id $upd")
                }
            }
        }
    }
}

data class TrecCarJudgment(val qid: String, val paragraphId: String, val judgment: Int=1) {
    val queryParts: List<String>
            get() = qid.split('/').map { decodeURL(it) }.reversed()
    val page: String
        get() = queryParts.last()
}

// Lex URLs into an escape, or single % or any other text.
val EscapeMatcher = "(%\\d{2}|%|[^%]*)".toRegex()
fun decodeURL(input: String): String {
    return EscapeMatcher.findAll(input).map { match ->
        val substr = match.value
        if (substr.length == 3 && substr[0] == '%') {
            val rest = substr.substring(1).toIntOrNull(16)
                    ?: return@map substr
            rest.toChar().toString()
        } else {
            substr
        }
    }.joinToString(separator = "") { it }
}

object TestDecode {
    @JvmStatic fun main(args: Array<String>) {
        (1 .. 4).forEach { fold ->
            val qrelsPath = File("/mnt/scratch/jfoley/trec-car/train/train.fold$fold.cbor.hierarchical.qrels")
            val (queries, _) = loadTrecCarDataset(qrelsPath)
            println("Fold $fold, Queries: ${queries.size}")
        }
    }
}

data class TrecCarDataset(val queries: Map<String, String>, val judgments: QuerySetJudgments)
fun loadTrecCarDataset(qrelsPath: File): TrecCarDataset {
    val judgments = ArrayList<TrecCarJudgment>()
    qrelsPath.smartDoLines { line ->
        val cols = line.split(SpacesRegex)
        if (cols.size == 4) {
            val qid = cols[0]
            //val unused = cols[1]
            val paragraphId = cols[2]
            val judgment = cols[3].toInt()
            judgments.add(TrecCarJudgment(qid, paragraphId, judgment))
        }
    }

    val queries = judgments.associate { Pair(it.qid, it.queryParts.joinToString(separator = "\t")) }
    val qrels = QuerySetJudgments(
            judgments.groupBy { it.qid }
                    .mapValues { (qid, v) ->
                        QueryJudgments(qid, v.associate { Pair(it.paragraphId, it.judgment) })
                    }
    )
    return TrecCarDataset(queries, qrels)
}

object CountDocuments {
    @JvmStatic fun main(args: Array<String>) {
        val argp = Parameters.parseArgs(args)
        IreneIndex(getTrecCarIndexParams(File(argp.get("index", "/mnt/scratch/jfoley/trec-car/paragraphs.irene2")))).use { index ->
            println(index.totalDocuments)
        }
    }
}

// QL performance: map=0.151 r-prec=0.114 recip_rank=0.211
// BM25 from paper: map=0.150 r-prec=0.118 recip_rank=0.216
// SDM-min-stop: map=0.156	r=0.486	r-prec=0.117	recip_rank=0.219
object Test200Baseline {
    @JvmStatic fun main(args: Array<String>) {
        val basePath = File("/mnt/scratch/jfoley/trec-car/")
        val argp = Parameters.parseArgs(args)
        //val qrelsPath = File(argp.get("qrels", File(basePath, "test200/train.test200.fold0.cbor.hierarchical.qrels").absolutePath))
        val qrelsPath = File(argp.get("qrels", File(basePath, "train/train.fold1.cbor.hierarchical.qrels").absolutePath))
        val measures = getEvaluators("map", "recip_rank", "r-prec", "r")
        val summary = NamedMeasures()

        val (queries, qrels) = loadTrecCarDataset(qrelsPath)
        println(queries.size)

        File(argp.get("output","trec-car-test200.irene-sdm.qlpool.jsonl.gz")).smartPrint { output ->
            IreneIndex(getTrecCarIndexParams(File(argp.get("index", "/mnt/scratch/jfoley/trec-car/paragraphs.irene2")))).use { index ->
                index.env.estimateStats = "min"
                val msg = CountingDebouncer(queries.size.toLong())
                queries.entries.parallelStream().map { (qid, qtext) ->
                    val qj = qrels[qid] ?: error("No judgments for $qid")
                    val terms = index.tokenize(qtext)
                    val qsdm = SequentialDependenceModel(terms, stopwords = inqueryStop)
                    //val ql = QueryLikelihood(terms)

                    val results = index.search(qsdm, 100)

                    val rawDocs = results.scoreDocs.associate { sdoc ->
                        val ldoc = index.document(sdoc.doc)!!
                        val fields = pmake {}
                        ldoc.fields.forEach { field ->
                            val name = field.name()!!
                            fields.putIfNotNull(name, field.stringValue())
                            fields.putIfNotNull(name, field.numericValue())
                        }
                        Pair(sdoc.doc, fields)
                    }
                    val docPs = results.scoreDocs.mapIndexed { i, sdoc ->
                        val fields = rawDocs[sdoc.doc]!!
                        val docName = fields.getString(index.idFieldName)!!
                        pmake {
                            set("id", docName)
                            set("pooling-score", sdoc.score)
                            set("rank", i + 1)
                            set("fields", fields)
                        }
                    }

                    val qjson = pmake {
                        set("qid", qid)
                        set("totalHits", results.totalHits)
                        set("docs", docPs)
                        set("qtext", qtext)
                        set("qterms", terms)
                    }

                    val gres = results.toQueryResults(index)

                    val computed = measures.mapValues { (_, fn) ->
                        try {
                            fn.evaluate(gres, qj)
                        } catch (e: Exception) {
                            println("Exception in eval: $e")
                            0.0
                        }
                    }

                    qjson.put("measures", Parameters.wrap(computed))

                    synchronized(summary) {
                        computed.forEach { m, score -> summary.push(m, score) }
                        msg.incr()?.let { upd ->
                            println(summary)
                            println(upd)
                        }
                    }

                    qjson
                }.sequential().forEach { qjson ->
                    output.println(qjson)
                }
            }
        }

        println("FINISHED.")
        println(summary)
    }
}
