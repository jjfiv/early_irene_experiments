package edu.umass.cics.ciir.irene.lang

import edu.umass.cics.ciir.iltr.RREnv
import edu.umass.cics.ciir.irene.CountStats
import edu.umass.cics.ciir.irene.DataNeeded
import edu.umass.cics.ciir.irene.createOptimizedMovementExpr
import edu.umass.cics.ciir.irene.lucene_try
import org.apache.lucene.index.Term
import java.util.*

typealias LuceneQuery = org.apache.lucene.search.Query

/**
 * [QExpr] is the base class for our typed inquery-like query language. Nodes  have [children], and can be [visit]ed, but they are also strongly typed, and can [deepCopy] themselves.
 *
 * A reasonable query may be composed of [TextExpr] at the leaves, [BM25Expr] in the middle and a [CombineExpr] across the terms, but much more sophisticated scoring models can be expressed.
 *
 * @author jfoley.
 */
sealed class QExpr {
    val trySingleChild: QExpr
        get() {
        if (children.size != 1) error("Looked for a child on a node with children: $this")
        return children[0]
    }
    abstract val children: List<QExpr>
    fun deepCopy(): QExpr = map {
        if (it is LeafExpr) {
            it.copyLeaf()
        } else it.deepCopy()
    }
    open fun applyEnvironment(env: RREnv) {}

    fun findTextNodes(): List<TextExpr> {
        val out = ArrayList<TextExpr>()
        visit {
            if (it is TextExpr) {
                out.add(it)
            }
        }
        return out
    }

    fun visit(each: (QExpr)->Unit) {
        each(this)
        children.forEach { it.visit(each) }
    }

    fun getStatsFields(): Set<String> {
        val out = HashSet<String>()
        visit { c ->
            if (c is TextExpr) {
                out.add(c.statsField())
            }
        }
        return out
    }
    fun getSingleStatsField(default: String): String {
        val fields = getStatsFields()
        return when(fields.size) {
            0 -> default
            1 -> fields.first()
            else -> error("Can't determine single field for $this")
        }
    }
    abstract fun map(mapper: (QExpr)->QExpr): QExpr

    // Get a weighted version of this node if weight is non-null.
    fun weighted(x: Double?) = if(x != null) WeightExpr(this, x) else this
}
data class MultiExpr(val namedExprs: Map<String, QExpr>): QExpr() {
    val names = namedExprs.keys.toList()
    override val children = names.map { namedExprs[it]!! }.toList()
    override fun map(mapper: (QExpr)->QExpr): MultiExpr = MultiExpr(namedExprs.mapValues { (_, v) -> mapper(v) })

}
sealed class LeafExpr : QExpr() {
    override val children: List<QExpr> get() = emptyList()
    abstract fun copyLeaf(): QExpr
    override fun map(mapper: (QExpr)->QExpr): QExpr = mapper(this)
}

/**
 * We will never score a document just because of any constant.
 * If you want that behavior, check out [AlwaysMatchExpr] that you can wrap these with.
 */
sealed class ConstExpr() : LeafExpr() { }
data class ConstScoreExpr(var x: Double): ConstExpr() {
    override fun copyLeaf(): QExpr = ConstScoreExpr(x)
}
data class ConstCountExpr(var x: Int, val lengths: LengthsExpr): ConstExpr() {
    override fun copyLeaf(): QExpr = ConstCountExpr(x, lengths)
}
data class ConstBoolExpr(var x: Boolean): ConstExpr() {
    override fun copyLeaf(): QExpr = ConstBoolExpr(x)
}
/**
 * For finding document candidates, consider this subtree to *always* cause a match.
 * Hope you put in an And (e.g., [AndExpr] or [OrderedWindowExpr]), or this will be extremely expensive.
 */
data class AlwaysMatchExpr(override var child: QExpr) : SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = AlwaysMatchExpr(mapper(child))
}

/**
 * For finding document candidates, never consider this subtree as a match. This is the opposite of [AlwaysMatchExpr].
 * Useful for "boost" style features that are expensive.
 * Don't use this much, we should be able to infer it in many cases, see [createOptimizedMovementExpr] and [simplifyBooleanExpr].
 */
fun NeverMatchExpr(child: QExpr) = RequireExpr(NeverMatchLeaf, child)
object NeverMatchLeaf : LeafExpr() {
    override fun copyLeaf() = this
}

data class WhitelistMatchExpr(var docNames: Set<String>? = null, var docIdentifiers: List<Int>? = null) : LeafExpr() {
    override fun applyEnvironment(env: RREnv) {
        if (docIdentifiers == null) {
            if (docNames == null) error("WhitelistMatchExpr must have *either* docNames or docIdentifiers to start.")
            docIdentifiers = env.lookupNames(docNames!!)
        }
    }
    override fun copyLeaf() = WhitelistMatchExpr(docNames, docIdentifiers)
}

data class LengthsExpr(var statsField: String?, var stats: CountStats? = null) : LeafExpr() {
    override fun applyEnvironment(env: RREnv) {
        if (statsField == null) {
            statsField = env.defaultField
        }
    }
    override fun copyLeaf() = LengthsExpr(statsField, stats)
}
sealed class OpExpr : QExpr() {
    abstract override var children: List<QExpr>
    fun mapChildren(mapper: (QExpr)->QExpr): List<QExpr> = children.map { mapper(it) }
}
sealed class SingleChildExpr : QExpr() {
    abstract var child: QExpr
    override val children: List<QExpr> get() = listOf(child)
}
/** Sync this class to Galago semantics. Consider every doc that has a match IFF cond has a match, using value, regardless of whether value also has a match. */
data class RequireExpr(var cond: QExpr, var value: QExpr): QExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = RequireExpr(mapper(cond), mapper(value))
    override val children: List<QExpr> get() = arrayListOf(cond, value)
}

/**
 * [TextExpr] represent a term [text] inside a [field] smoothed with statistics [stats] derived from [statsField]. By default [field] and [statsField] will be the same, and will be filled with sane defaults if left empty.
 */
data class TextExpr(var text: String, private var field: String? = null, private var statsField: String? = null, var stats: CountStats? = null, var needed: DataNeeded = DataNeeded.DOCS) : LeafExpr() {
    override fun copyLeaf() = TextExpr(text, field, statsField, stats, needed)
    constructor(term: Term) : this(term.text(), term.field())

    override fun applyEnvironment(env: RREnv) {
        if (field == null) {
            field = env.defaultField
        }
        if (statsField == null) {
            statsField = env.defaultField
        }
    }
    fun countsField(): String = field ?: error("No primary field for $this")
    fun statsField(): String = statsField ?: field ?: error("No stats field for $this")
}
data class SynonymExpr(override var children: List<QExpr>): OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = SynonymExpr(mapChildren(mapper))
}
data class LuceneExpr(val rawQuery: String, var query: LuceneQuery? = null ) : LeafExpr() {
    fun parse(env: IreneQueryLanguage) = LuceneExpr(rawQuery,
            lucene_try {
                env.luceneQueryParser.parse(rawQuery)
            } ?: error("Could not parse lucene expression: ``${rawQuery}''"))
    override fun copyLeaf() = LuceneExpr(rawQuery, query)
}


data class AndExpr(override var children: List<QExpr>) : OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = AndExpr(mapChildren(mapper))
}
data class OrExpr(override var children: List<QExpr>) : OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = OrExpr(mapChildren(mapper))
}
fun SumExpr(vararg children: QExpr) = SumExpr(children.toList())
fun SumExpr(children: List<QExpr>) = CombineExpr(children, children.map { 1.0 })
fun MeanExpr(vararg children: QExpr) = MeanExpr(children.toList())
fun MeanExpr(children: List<QExpr>) = CombineExpr(children, children.map { 1.0 / children.size.toDouble() })
data class CombineExpr(override var children: List<QExpr>, var weights: List<Double>) : OpExpr() {
    val entries: List<Pair<QExpr, Double>> get() = children.zip(weights)
    override fun map(mapper: (QExpr) -> QExpr): QExpr {
        val newChildren = mapChildren(mapper)
        assert(newChildren.size == weights.size)
        return CombineExpr(newChildren, weights)
    }
}
data class MultExpr(override var children: List<QExpr>) : OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = MultExpr(mapChildren(mapper))
}
data class MaxExpr(override var children: List<QExpr>) : OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = MaxExpr(mapChildren(mapper))
}

/** For estimating the lower-bound of an [OrderedWindowExpr]. When all terms occur, which is smallest? */
data class SmallerCountExpr(override var children: List<QExpr>): OpExpr() {
    init {
        assert(children.size >= 2)
    }
    override fun map(mapper: (QExpr) -> QExpr): QExpr = SmallerCountExpr(mapChildren(mapper))
}
/** For estimating the ceiling of an [UnorderedWindowExpr]. When all terms occur, which is biggest? */
data class UnorderedWindowCeilingExpr(override var children: List<QExpr>, var width: Int=8): OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = UnorderedWindowCeilingExpr(mapChildren(mapper), width)
}
data class OrderedWindowExpr(override var children: List<QExpr>, var step: Int=1) : OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = OrderedWindowExpr(mapChildren(mapper), step)
}

/**
 * This [UnorderedWindowExpr] matches the computation in Galago. Huston et al. found that the particular unordered window does not matter so much, so we recommend using [ProxExpr] instead. [Tech Report](http://ciir-publications.cs.umass.edu/pub/web/getpdf.php?id=1142).
 */
data class UnorderedWindowExpr(override var children: List<QExpr>, var width: Int=8) : OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = UnorderedWindowExpr(mapChildren(mapper), width)
}

data class ProxExpr(override var children: List<QExpr>, var width: Int=8): OpExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = ProxExpr(mapChildren(mapper), width)
}

data class WeightExpr(override var child: QExpr, var weight: Double = 1.0) : SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = WeightExpr(mapper(child), weight)
}

data class DirQLExpr(override var child: QExpr, var mu: Double? = null): SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = DirQLExpr(mapper(child), mu)
}
data class AbsoluteDiscountingQLExpr(override var child: QExpr, var delta: Double? = null): SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = AbsoluteDiscountingQLExpr(mapper(child), delta)
}
data class BM25Expr(override var child: QExpr, var b: Double? = null, var k: Double? = null): SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = BM25Expr(mapper(child), b, k)
}
data class CountToScoreExpr(override var child: QExpr): SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = CountToScoreExpr(mapper(child))
}
data class BoolToScoreExpr(override var child: QExpr, var trueScore: Double=1.0, var falseScore: Double=0.0): SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = BoolToScoreExpr(mapper(child), trueScore, falseScore)
}
data class CountToBoolExpr(override var child: QExpr, var gt: Int = 0): SingleChildExpr() {
    override fun map(mapper: (QExpr) -> QExpr): QExpr = CountToBoolExpr(mapper(child), gt)
}

