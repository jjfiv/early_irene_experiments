package edu.umass.cics.ciir.irene.scoring

import edu.umass.cics.ciir.irene.*
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermContext
import org.apache.lucene.search.*

/**
 *
 * @author jfoley.
 */

data class IQContext(val iqm: IreneQueryModel, val context: LeafReaderContext) {
    val env = iqm.env
    val searcher = iqm.index.searcher
    val lengths = HashMap<String, NumericDocValues>()

    private fun getLengths(field: String) = lengths.computeIfAbsent(field, { missing ->
        lucene_try { context.reader().getNormValues(missing) } ?: error("Couldn't find norms for ``$missing''.")
    })

    fun create(term: Term, needed: DataNeeded, stats: CountStats): QueryEvalNode {
        return create(term, needed, stats, getLengths(term.field()))
    }
    fun create(term: Term, needed: DataNeeded, stats: CountStats, lengths: NumericDocValues): QueryEvalNode {
        val termContext = TermContext.build(searcher.topReaderContext, term)!!
        val state = termContext[context.ord] ?: return LuceneMissingTerm(term, stats, lengths)
        val termIter = context.reader().terms(term.field()).iterator()
        termIter.seekExact(term.bytes(), state)
        val postings = termIter.postings(null, needed.textFlags())
        return when(needed) {
            DataNeeded.DOCS -> LuceneTermDocs(stats, postings)
            DataNeeded.COUNTS -> LuceneTermCounts(stats, postings, lengths)
            DataNeeded.POSITIONS -> LuceneTermPositions(stats, postings, lengths)
            DataNeeded.SCORES -> error("Impossible!")
        }
    }
    fun numDocs(): Int = context.reader().numDocs()
    fun createLengths(field: String, countStats: CountStats): QueryEvalNode {
        return LuceneDocLengths(countStats, getLengths(field))
    }
}

private class IQModelWeight(val q: QExpr, val m: QExpr, val iqm: IreneQueryModel) : Weight(iqm) {
    override fun extractTerms(terms: MutableSet<Term>) {
        TODO("not implemented")
    }

    override fun explain(context: LeafReaderContext, doc: Int): Explanation {
        val ctx = IQContext(iqm, context)
        return exprToEval(q, ctx).explain(doc)
    }

    override fun scorer(context: LeafReaderContext): Scorer {
        val ctx = IQContext(iqm, context)
        val qExpr = exprToEval(q, ctx)
        if (q === m) {
            return IreneQueryScorer(q, QueryEvalNodeIter(qExpr))
        } else {
            val mExpr = exprToEval(m, ctx)
            val iter = OptimizedMovementIter(mExpr, qExpr)
            return IreneQueryScorer(q, iter)
        }
    }
}

open class QueryEvalNodeIter(val node: QueryEvalNode) : DocIdSetIterator() {
    open val score: QueryEvalNode = node
    open fun start() {
        node.nextMatching(0)
    }
    override fun advance(target: Int): Int = node.nextMatching(target)
    override fun nextDoc(): Int = advance(docID() + 1)
    override fun docID() = node.docID()
    open fun score(doc: Int): Float {
        node.syncTo(doc)
        return node.score(doc)
    }
    open fun count(doc: Int): Int {
        node.syncTo(doc)
        return node.count(doc)
    }
    override fun cost(): Long = node.estimateDF()
}


class OptimizedMovementIter(val movement: QueryEvalNode, override val score: QueryEvalNode): QueryEvalNodeIter(movement) {
    override fun start() {
        advance(0)
    }
    override fun advance(target: Int): Int {
        var dest = target
        while (!score.done) {
            val nextMatch = movement.nextMatching(dest)
            if (nextMatch == NO_MORE_DOCS) break
            if (score.matches(nextMatch)) {
                return nextMatch
            } else {
                dest++
            }
        }
        return NO_MORE_DOCS
    }
    override fun score(doc: Int): Float {
        node.syncTo(doc)
        score.syncTo(doc)
        return score.score(doc)
    }
    override fun count(doc: Int): Int {
        node.syncTo(doc)
        score.syncTo(doc)
        return score.count(doc)
    }
}

class IreneQueryScorer(val q: QExpr, val iter: QueryEvalNodeIter) : Scorer(null) {
    init {
        iter.start()
    }
    val eval: QueryEvalNode = iter.score
    override fun docID(): Int = iter.docID()
    override fun iterator(): DocIdSetIterator = iter
    override fun score(): Float {
        val returned = iter.score(docID())
        if (java.lang.Float.isInfinite(returned)) {
            throw RuntimeException("Infinite response for document: ${eval.docID()} ${eval.explain(iter.docID())}")
            //return -Float.MAX_VALUE
        }
        return returned
    }
    override fun freq(): Int = iter.count(docID())
}

class IreneQueryModel(val index: IreneIndex, val env: IreneQueryLanguage, q: QExpr) : LuceneQuery() {
    val exec = env.prepare(q)
    var movement = if (env.optimizeMovement) {
        val m = env.prepare(createOptimizedMovementExpr(exec))
        println(m)
        m
    } else {
        exec
    }

    override fun createWeight(searcher: IndexSearcher?, needsScores: Boolean, boost: Float): Weight {
        return IQModelWeight(exec, movement, this)
    }
    override fun hashCode(): Int {
        return exec.hashCode()
    }
    override fun equals(other: Any?): Boolean {
        if (other is IreneQueryModel) {
            return exec == other.exec
        }
        return false
    }
    override fun toString(field: String?): String {
        return exec.toString()
    }
}

