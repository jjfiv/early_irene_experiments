package edu.umass.cics.ciir.irene.scoring

import edu.umass.cics.ciir.IntList
import edu.umass.cics.ciir.irene.CountStats
import edu.umass.cics.ciir.irene.DataNeeded
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.index.PostingsEnum
import org.apache.lucene.index.Term
import org.apache.lucene.search.Explanation

/**
 *
 * @author jfoley.
 */
data class LuceneMissingTerm(val term: Term, val stats: CountStats, val lengths: NumericDocValues) : LeafEvalNode, QueryEvalNode {
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

class LuceneTermPostings(val needed: DataNeeded, val stats: CountStats, val postings: PostingsEnum, val lengths: NumericDocValues) : LeafEvalNode, QueryEvalNode {
    // Lucene requires we call nextDoc() before doing anything else.
    init { postings.nextDoc() }

    var posDoc = -1
    var positions = IntList()
    override fun positions(doc: Int): PositionsIter {
        assert(needed == DataNeeded.POSITIONS)
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

    override fun docID(): Int = postings.docID()
    override fun advance(target: Int): Int {
        if (postings.docID() < target) {
            return postings.advance(target)
        }
        return postings.docID()
    }
    override fun score(doc: Int): Float = count(doc).toFloat()
    override fun count(doc: Int): Int {
        assert(needed == DataNeeded.COUNTS)
        if(matches(doc)) {
            return postings.freq()
        }
        return 0
    }

    override fun matches(doc: Int): Boolean {
        syncTo(doc)
        return docID() == doc
    }

    override fun explain(doc: Int): Explanation {
        if (matches(doc)) {
            return Explanation.match(count(doc).toFloat(), "@doc=$doc, lengths@${lengths.docID()}")
        } else {
            return Explanation.noMatch("@doc=${postings.docID()} doc=$doc, lengths@=${lengths.docID()}")
        }
    }

    override fun estimateDF(): Long = stats.df
    override fun getCountStats(): CountStats = stats
    override fun length(doc: Int): Int {
        if (lengths.advanceExact(doc)) {
            return lengths.longValue().toInt()
        }
        return 0;
    }

}
