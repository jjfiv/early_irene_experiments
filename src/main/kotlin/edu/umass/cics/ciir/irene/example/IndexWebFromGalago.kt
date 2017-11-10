package edu.umass.cics.ciir.irene.example

import edu.umass.cics.ciir.dbpedia.ShardWriters
import edu.umass.cics.ciir.irene.IndexParams
import edu.umass.cics.ciir.irene.IreneIndexer
import edu.umass.cics.ciir.sprf.DataPaths
import edu.umass.cics.ciir.sprf.forAllGDocs
import edu.umass.cics.ciir.sprf.pmake
import edu.umass.cics.ciir.sprf.printer
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexableField
import org.jsoup.Jsoup
import org.lemurproject.galago.utility.Parameters
import org.lemurproject.galago.utility.StreamCreator
import java.io.File
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/**
 * @author jfoley
 */

fun FileLogger(path: String) = Logger.getAnonymousLogger().apply {
    useParentHandlers = false
    addHandler(FileHandler(path).apply {
        formatter = SimpleFormatter()
    })
}

val PunctuationRegex = "\\p{Punct}".toRegex()

fun main(args: Array<String>) {
    val argp = Parameters.parseArgs(args)
    val dsName = argp.get("dataset", "gov2")
    val dataset = DataPaths.get(dsName)

    val indexF = File(argp.get("path", "$dsName.irene2"))
    val params = IndexParams().apply {
        create()
        withPath(indexF)
        withAnalyzer("url", WhitespaceAnalyzer())
    }
    val logger = FileLogger(argp.get("logger", "${indexF.absolutePath}.log"))

    IreneIndexer(params).use { writer ->
        dataset.getIndex().use { retr ->
            retr.forAllGDocs { gdoc ->
                val id = gdoc.name!!
                val meta = gdoc.metadata ?: emptyMap()
                val url = meta["url"]
                val text = gdoc.text
                val html = try {
                    Jsoup.parse(text)
                } catch (e: Throwable) {
                    logger.log(Level.WARNING, "Jsoup Exception", e)
                    return@forAllGDocs
                }

                val doc = arrayListOf<IndexableField>()
                doc.add(StringField("id", id, Field.Store.YES))
                if (!url.isNullOrBlank()) {
                    doc.add(StringField("stored-url", url, Field.Store.YES))
                    val tokenized = url!!.split(PunctuationRegex).joinToString(" ")
                    doc.add(TextField("url", tokenized, Field.Store.YES))
                }

                val title = html.title()
                if (!title.isNullOrBlank()) {
                    doc.add(TextField("title", title, Field.Store.YES))
                }
                val body = html.body()?.text()
                if (!body.isNullOrBlank()) {
                    doc.add(TextField("body", body, Field.Store.YES))
                }
                doc.add(TextField("document", html.text(), Field.Store.YES))

                writer.push(doc)
                //println("$id $title $url")
            }
        }
    }
}


fun galagoScrubUrl(input: String?): String? {
    var url = input ?: return null
    if (url.isEmpty()) return null
    // remove a leading pound sign
    if (url[url.length - 1] == '#') {
        url = url.substring(0, url.length - 1)        // make it lowercase
    }
    url = url.toLowerCase()

    // remove a port number, if it's the default number
    url = url.replace(":80/", "/")
    if (url.endsWith(":80")) {
        url = url.replace(":80", "")
    }
    // remove trailing slashes
    while (url[url.length - 1] == '/') {
        url = url.substring(0, url.length - 1)
    }
    return url.toLowerCase()
}

object ExtractLinks {
    fun String?.limit(n: Int): String? {
        if (this == null) return null
        if (this.length > n) {
            return this.substring(n)
        }
        return this
    }
    @JvmStatic fun main(args: Array<String>) {
        val argp = Parameters.parseArgs(args)
        val dsName = argp.get("dataset", "gov2")
        val dataset = DataPaths.get(dsName)
        val outDir = File(argp.get("output", "$dsName.links"))
        val anchorLimit = argp.get("maxAnchorSize", 1 shl 12);
        val shards = argp.get("shards", 50)

        val logger = FileLogger(argp.get("logger", "${outDir.absolutePath}.log"))

        StreamCreator.openOutputStream(File(outDir, "urls.tsv.gz")).printer().use { urlTable ->
            ShardWriters(outDir, shards, "inlinks.jsonl.gz").use { writers ->
                dataset.getIndex().use { retr ->
                    retr.forAllGDocs { gdoc ->
                        val id = gdoc.name!!
                        val meta = gdoc.metadata ?: emptyMap()
                        val url = galagoScrubUrl(meta["url"])


                        val html = try {
                            Jsoup.parse(gdoc.text)
                        } catch (e: Throwable) {
                            logger.log(Level.WARNING, "Jsoup Exception", e)
                            return@forAllGDocs
                        }

                        val outlinks = html.select("a")
                        urlTable.println("$id\t${outlinks.size}\t${url}")
                        // send this url=id entry to the appropriate shard
                        writers.hashed(url).println(pmake {
                            set("id", id)
                            set("url", url)
                            set("outlinks", outlinks.size)
                        })

                        outlinks.forEach { anchor ->
                            val dest = galagoScrubUrl(anchor.attr("abs:href")) ?: ""
                            val text = anchor.text().limit(anchorLimit) ?: ""

                            // send this "from id with text" to appropriate shard.
                            if (!dest.isBlank()) {
                                writers.hashed(dest).println(pmake {
                                    set("id", id)
                                    set("dest", dest)
                                    set("text", text)
                                })
                            }

                        }
                    }
                }
            }
        }
    }
}