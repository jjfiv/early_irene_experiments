package edu.umass.cics.ciir.irene.scoring

import edu.umass.cics.ciir.irene.*
import edu.umass.cics.ciir.sprf.*
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Explanation
import java.io.File

/**
 * This class translates the public-facing query language (QExpr and subclasses) to a set of private-facing operators (QueryEvalNode and subclasses).
 * @author jfoley.
 */

fun exprToEval(q: QExpr, ctx: IQContext): QueryEvalNode = when(q) {
    is TextExpr -> ctx.create(Term(q.field, q.text), q.needed, q.stats ?: error("Missed applyIndex pass."))
    is LuceneExpr -> TODO()
    is SynonymExpr -> TODO()
    is AndExpr -> BooleanAndEval(q.children.map { exprToEval(it, ctx) })
    is OrExpr -> BooleanOrEval(q.children.map { exprToEval(it, ctx) })
    is CombineExpr -> WeightedSumEval(
            q.children.map { exprToEval(it, ctx) },
            q.weights.map { it }.toDoubleArray())
    is MultExpr -> TODO()
    is MaxExpr -> MaxEval(q.children.map { exprToEval(it, ctx) })
    is WeightExpr -> WeightedEval(exprToEval(q.child, ctx), q.weight.toFloat())
    is DirQLExpr -> DirichletSmoothingEval(exprToEval(q.child, ctx) as CountEvalNode, q.mu!!)
    is BM25Expr -> TODO()
    is CountToScoreExpr -> TODO()
    is BoolToScoreExpr -> TODO()
    is CountToBoolExpr -> TODO()
    is RequireExpr -> RequireEval(exprToEval(q.cond, ctx), exprToEval(q.value, ctx))
    is OrderedWindowExpr -> OrderedWindow(LazyCountStats(q.copy(), ctx.index), q.children.map { exprToEval(it, ctx) as PositionsEvalNode }, q.step)
}


const val NO_MORE_DOCS = DocIdSetIterator.NO_MORE_DOCS
interface QueryEvalNode {
    fun docID(): Int
    fun score(doc: Int): Float
    fun count(doc: Int): Int
    fun matches(doc: Int): Boolean
    fun explain(doc: Int): Explanation
    fun estimateDF(): Long

    fun nextMatching(doc: Int): Int {
        var id = doc
        while(id < NO_MORE_DOCS) {
            if (matches(id)) {
                return id
            }
            id = advance(id+1)
        }
        return NO_MORE_DOCS
    }
    fun nextDoc(): Int {
        return nextMatching(docID()+1)
    }
    fun advance(target: Int): Int
    val done: Boolean get() = docID() == NO_MORE_DOCS
    fun syncTo(target: Int) {
        if (docID() < target) {
            advance(target)
        }
    }
}
interface CountEvalNode : QueryEvalNode {
    fun getCountStats(): CountStats
    fun length(doc: Int): Int
}
interface PositionsEvalNode : CountEvalNode {
    fun positions(doc: Int): PositionsIter
}

private class RequireEval(val cond: QueryEvalNode, val score: QueryEvalNode, val miss: Float=-Float.MAX_VALUE): QueryEvalNode {
    override fun score(doc: Int): Float = if (cond.matches(doc)) { score.score(doc) } else miss
    override fun count(doc: Int): Int = if (cond.matches(doc)) { score.count(doc) } else 0
    override fun matches(doc: Int): Boolean = cond.matches(doc) && score.matches(doc)
    override fun explain(doc: Int): Explanation {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
    override fun estimateDF(): Long = minOf(score.estimateDF(), cond.estimateDF())
    override fun docID(): Int = cond.docID()
    override fun advance(target: Int): Int = cond.advance(target)
}

abstract class RecursiveEval<out T : QueryEvalNode>(val children: List<T>) : QueryEvalNode {
    val className = this.javaClass.simpleName
    val N = children.size
    override fun syncTo(target: Int) {
        children.forEach { it.syncTo(target) }
    }
    override fun explain(doc: Int): Explanation {
        val expls = children.map { it.explain(doc) }
        if (matches(doc)) {
            return Explanation.match(score(doc), "$className.Match", expls)
        }
        return Explanation.noMatch("$className.Miss", expls)
    }
}
abstract class OrEval<out T : QueryEvalNode>(children: List<T>) : RecursiveEval<T>(children) {
    val cost = children.map { it.estimateDF() }.max() ?: 0L
    val moveChildren = children.sortedByDescending { it.estimateDF() }
    override fun docID(): Int = children.map { it.docID() }.min()!!
    override fun advance(target: Int): Int {
        var nextMin = NO_MORE_DOCS
        for (child in moveChildren) {
            var where = child.docID()
            if (where < target) {
                where = child.nextMatching(target)
            }
            nextMin = minOf(nextMin, where)
        }
        return nextMin
    }
    override fun estimateDF(): Long = cost

    override fun matches(doc: Int): Boolean {
        syncTo(doc)
        return children.any { it.matches(doc) }
    }
}

abstract class AndEval<out T : QueryEvalNode>(children: List<T>) : RecursiveEval<T>(children) {
    private var current: Int = 0
    val cost = children.map { it.estimateDF() }.min() ?: 0L
    val moveChildren = children.sortedBy { it.estimateDF() }
    init {
        advanceToMatch()
    }

    override fun docID(): Int = current
    fun advanceToMatch(): Int {
        while(true) {
            var match = true
            moveChildren.forEach { child ->
                var pos = child.docID()
                if (pos < current) {
                    pos = child.advance(current)
                    if (pos == NO_MORE_DOCS) return NO_MORE_DOCS
                }
                if (pos > current) {
                    current = pos
                    match = false
                    return@forEach
                }
            }

            if (match) return current
        }
    }

    override fun advance(target: Int): Int {
        current = maxOf(current, target)
        return advanceToMatch()
    }
    override fun estimateDF(): Long = cost

    override fun matches(doc: Int): Boolean {
        syncTo(doc)
        return children.all { it.matches(doc) }
    }
}

private class BooleanOrEval(children: List<QueryEvalNode>): OrEval<QueryEvalNode>(children) {
    override fun score(doc: Int): Float = if (matches(doc)) { 1f } else { 0f }
    override fun count(doc: Int): Int = if (matches(doc)) { 1 } else { 0 }
}
private class BooleanAndEval(children: List<QueryEvalNode>): AndEval<QueryEvalNode>(children) {
    override fun score(doc: Int): Float = if (matches(doc)) { 1f } else { 0f }
    override fun count(doc: Int): Int = if (matches(doc)) { 1 } else { 0 }
}

private class MaxEval(children: List<QueryEvalNode>) : OrEval<QueryEvalNode>(children) {
    override fun score(doc: Int): Float {
        var sum = 0f
        children.forEach {
            sum = maxOf(sum, it.score(doc))
        }
        return sum
    }
    override fun count(doc: Int): Int {
        var sum = 0
        children.forEach {
            sum = maxOf(sum, it.count(doc))
        }
        return sum
    }
}
// Also known as #combine for you galago/indri folks.
private class WeightedSumEval(children: List<QueryEvalNode>, val weights: DoubleArray) : OrEval<QueryEvalNode>(children) {
    override fun score(doc: Int): Float {

        var sum = 0.0
        children.forEachIndexed { i, child ->
            child.syncTo(doc)
            sum += weights[i] * child.score(doc)
        }
        return sum.toFloat()
    }

    override fun explain(doc: Int): Explanation {
        val expls = children.map { it.explain(doc) }
        if (matches(doc)) {
            return Explanation.match(score(doc), "$className.Match ${weights.toList()}", expls)
        }
        return Explanation.noMatch("$className.Miss ${weights.toList()}", expls)
    }

    override fun count(doc: Int): Int = error("Calling counts on WeightedSumEval is nonsense.")
    init { assert(weights.size == children.size, {"Weights provided to WeightedSumEval must exist for all children."}) }
}

private abstract class SingleChildEval<out T : QueryEvalNode> : QueryEvalNode {
    abstract val child: T
    override fun docID(): Int = child.docID()
    override fun advance(target: Int): Int = child.advance(target)
    override fun estimateDF(): Long = child.estimateDF()
    override fun matches(doc: Int): Boolean {
        child.syncTo(doc)
        return child.matches(doc)
    }
    override fun syncTo(target: Int) = child.syncTo(target)
}

private class WeightedEval(override val child: QueryEvalNode, val weight: Float): SingleChildEval<QueryEvalNode>() {
    override fun score(doc: Int): Float = weight * child.score(doc)
    override fun count(doc: Int): Int = error("Weighted($weight).count()")
    override fun explain(doc: Int): Explanation {
        val orig = child.score(doc)
        if (child.matches(doc)) {
            return Explanation.match(weight*orig, "Weighted@$doc = $weight * $orig")
        } else {
            return Explanation.noMatch("Weighted.Miss@$doc (${weight*orig} = $weight * $orig)")
        }
    }
}

private class DirichletSmoothingEval(override val child: CountEvalNode, val mu: Double) : SingleChildEval<CountEvalNode>() {
    val background = mu * child.getCountStats().nonzeroCountProbability()
    override fun score(doc: Int): Float {
        val c = child.count(doc).toDouble()
        val length = child.length(doc).toDouble()
        return Math.log((c+ background) / (length + mu)).toFloat()
    }
    override fun count(doc: Int): Int = TODO("not yet")
    override fun explain(doc: Int): Explanation {
        val c = child.count(doc)
        val length = child.length(doc)
        if (c > 0) {
            return Explanation.match(score(doc), "$c/$length with mu=$mu, bg=$background dirichlet smoothing. ${child.getCountStats()}", listOf(child.explain(doc)))
        } else {
            return Explanation.noMatch("score=${score(doc)} or $c/$length with mu=$mu, bg=$background dirichlet smoothing ${child.getCountStats()} ${child.getCountStats().nonzeroCountProbability()}.", listOf(child.explain(doc)))
        }
    }
}

fun main(args: Array<String>) {
    val dataset = DataPaths.Robust
    val qrels = dataset.getQueryJudgments()
    val queries = dataset.getTitleQueries()
    val evals = getEvaluators(listOf("ap", "ndcg"))
    val ms = NamedMeasures()

    dataset.getIndex().use { galago ->
        IreneIndex(IndexParams().apply {
            withPath(File("robust.irene2"))
        }).use { index ->
            val mu = index.getAverageDL("body")
            println(index.getStats(Term("body", "president")))

            queries.forEach { qid, qtext ->
                //if (qid != "301") return@forEach
                println("$qid $qtext")
                val qterms = index.analyzer.tokenize("body", qtext)
                val q = MeanExpr(qterms.map { DirQLExpr(TextExpr(it)) })

                val gq = GExpr("combine").apply { addTerms(qterms) }

                val top5 = galago.transformAndExecuteQuery(gq, pmake {
                    set("requested", 5)
                    set("annotate", true)
                    set("processingModel", "rankeddocument")
                }).scoredDocuments
                val topK = index.search(q, 1000)
                val results = topK.toQueryResults(index)

                //if (argp.get("printTopK", false)) {
                (0 until Math.min(5, topK.totalHits.toInt())).forEach { i ->
                    val id = results[i]
                    val gd = top5[i]

                    if (i == 0 && id.name != gd.name) {
                        println(gd.annotation)
                        val missed = index.documentById(gd.name)!!
                        println("Missed document=$missed")
                        println(index.explain(q, missed))
                    }

                    println("${id.rank}\t${id.name}\t${id.score}")
                    println("${gd.rank}\t${gd.name}\t${gd.score}")
                }
                //}

                val queryJudgments = qrels[qid]!!
                evals.forEach { measure, evalfn ->
                    val score = try {
                        evalfn.evaluate(results, queryJudgments)
                    } catch (npe: NullPointerException) {
                        System.err.println("NULL in eval...")
                        -Double.MAX_VALUE
                    }
                    ms.push("$measure.irene2", score)
                }

                println(ms.means())
            }
        }
        println(ms.means())
    }
}