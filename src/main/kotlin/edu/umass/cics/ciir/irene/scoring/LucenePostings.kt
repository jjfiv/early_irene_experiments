package edu.umass.cics.ciir.irene.scoring

import edu.umass.cics.ciir.IntList
import edu.umass.cics.ciir.irene.CountStats
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.index.PostingsEnum
import org.apache.lucene.index.Term
import org.apache.lucene.search.Explanation

/**
 *
 * @author jfoley.
 */
data class LuceneMissingTerm(val term: Term, val stats: CountStats, val lengths: NumericDocValues) : PositionsEvalNode, QueryEvalNode {
    override fun positions(doc: Int): PositionsIter = error("Don't ask for positions if count is zero!")
    override fun docID(): Int = NO_MORE_DOCS
    override fun advance(target: Int): Int = NO_MORE_DOCS
    override fun score(doc: Int) = 0f
    override fun count(doc: Int) = 0
    override fun matches(doc: Int) = false
    override fun explain(doc: Int) = Explanation.match(0.0f, "MissingTerm-$term length=${length(doc)}")
    override fun estimateDF() = 0L
    override fun getCountStats(): CountStats = stats
    override fun length(doc: Int): Int {
        if (lengths.advanceExact(doc)) {
            return lengths.longValue().toInt()
        }
        return 0
    }
}

abstract class LuceneTermFeature(val stats: CountStats, val postings: PostingsEnum) : QueryEvalNode {
    // Lucene requires we call nextDoc() before doing anything else.
    init { postings.nextDoc() }

    override fun docID(): Int = postings.docID()
    override fun advance(target: Int): Int {
        if (postings.docID() < target) {
            return postings.advance(target)
        }
        return postings.docID()
    }

    override fun matches(doc: Int): Boolean {
        syncTo(doc)
        return docID() == doc
    }

    override fun explain(doc: Int): Explanation {
        if (matches(doc)) {
            return Explanation.match(count(doc).toFloat(), "@doc=$doc")
        } else {
            return Explanation.noMatch("@doc=${postings.docID()} doc=$doc")
        }
    }

    override fun estimateDF(): Long = stats.df
}

open class LuceneTermDocs(stats: CountStats, postings: PostingsEnum) : LuceneTermFeature(stats, postings) {
    override fun score(doc: Int): Float = count(doc).toFloat()
    override fun count(doc: Int): Int = if (matches(doc)) 1 else 0
}
open class LuceneTermCounts(stats: CountStats, postings: PostingsEnum, val lengths: NumericDocValues) : LuceneTermDocs(stats, postings), CountEvalNode {
    override fun score(doc: Int): Float = count(doc).toFloat()
    override fun count(doc: Int): Int {
        if(matches(doc)) {
            return postings.freq()
        }
        return 0
    }
    override fun getCountStats(): CountStats = stats
    override fun length(doc: Int): Int {
        if (lengths.advanceExact(doc)) {
            return lengths.longValue().toInt()
        }
        return 0;
    }

    override fun explain(doc: Int): Explanation {
        if (matches(doc)) {
            return Explanation.match(count(doc).toFloat(), "@doc=$doc, lengths@${lengths.docID()}")
        } else {
            return Explanation.noMatch("@doc=${postings.docID()} doc=$doc, lengths@=${lengths.docID()}")
        }
    }

}
class LuceneTermPositions(stats: CountStats, postings: PostingsEnum, lengths: NumericDocValues) : LuceneTermCounts(stats, postings, lengths), PositionsEvalNode {
    var posDoc = -1
    var positions = IntList()
    override fun positions(doc: Int): PositionsIter {
        if (posDoc != doc) {
            posDoc = doc
            positions.clear()
            val count = count(doc)
            if (count == 0) error("Don't ask for positions when count is zero.")

            (0 until count).forEach {
                positions.push(postings.nextPosition())
            }
        }
        return PositionsIter(positions.unsafeArray(), positions.fill)
    }

    override fun explain(doc: Int): Explanation {
        if (matches(doc)) {
            return Explanation.match(count(doc).toFloat(), "@doc=$doc, lengths@${lengths.docID()} positions=${positions(doc)}")
        } else {
            return Explanation.noMatch("@doc=${postings.docID()} doc=$doc, lengths@=${lengths.docID()} positions=[]")
        }
    }
}

