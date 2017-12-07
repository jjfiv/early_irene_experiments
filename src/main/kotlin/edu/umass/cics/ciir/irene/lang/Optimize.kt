package edu.umass.cics.ciir.irene.lang

import edu.umass.cics.ciir.iltr.RREnv
import edu.umass.cics.ciir.irene.DataNeeded
import edu.umass.cics.ciir.sprf.incr

/**
 *
 * @author jfoley.
 */
fun simplify(q: QExpr): QExpr {
    var pq = q.deepCopy()

    // combine weights and boolean nodes until query stops changing.
    var changed = true
    while(changed) {
        changed = false

        // Try to combine weights:
        combineWeights(pq)?.let {
            changed = true
            pq = it
        }

        // Try to flatten ANDs and ORs
        simplifyBooleanExpr(pq)?.let {
            changed = true
            pq = it
        }
    }

    return pq
}

fun computeQExprStats(index: RREnv, root: QExpr) {
    root.visit { q ->
        when (q) {
            is TextExpr -> {
                q.stats = index.getStats(q.text, q.statsField())
            }
            is LengthsExpr -> {
                q.stats = index.fieldStats(q.statsField!!)
            }
            else -> { }
        }
    }
}

class CombineWeightsFixedPoint {
    var changed: Boolean = false
    var iter = 0

    fun combineRedundantCombineChildren(q: CombineExpr): CombineExpr {
        // accumulate weights across redundant nodes:
        val weights = HashMap<QExpr, Double>()
        q.entries.forEach { (child, weight) ->
            weights.incr(child, weight)
        }

        // create new children:
        val newChildren = arrayListOf<QExpr>()
        val newWeights = arrayListOf<Double>()
        weights.forEach { c, w ->
            newChildren.add(combineWeights(c, this))
            newWeights.add(w)
        }
        changed = true

        // reduce children recursively:
        return CombineExpr(newChildren.map { combineWeights(it, this) }, newWeights)
    }

    fun flattenCombineExpr(q: CombineExpr): CombineExpr {
        val newChildren = arrayListOf<QExpr>()
        val newWeights = arrayListOf<Double>()
        q.entries.forEach { (c, w) ->
            if (c is CombineExpr) {
                // flatten combine(combine(...))
                c.children.zip(c.weights).forEach { (cc, cw) ->
                    newChildren.add(cc)
                    newWeights.add(w * cw)
                }
                changed = true
            } else if (c is WeightExpr) {
                //println("combine(...weight(x)..) -> combine(...x...)")
                newChildren.add(c.child)
                newWeights.add(c.weight * w)
                changed = true
            } else {
                // keep regular children around
                newChildren.add(c)
                newWeights.add(w)
            }
        }
        return CombineExpr(newChildren.map { combineWeights(it, this) }, newWeights)
    }
}

fun combineWeights(q: QExpr): QExpr? {
    val ctx = CombineWeightsFixedPoint()

    var anyChange = false
    var reducedQ = q
    // Call combineWeights until the query stops changing.
    do {
        ctx.changed = false
        reducedQ = combineWeights(reducedQ, ctx)
        anyChange = anyChange or ctx.changed
        ctx.iter++
    } while(ctx.changed)

    if (anyChange) {
        return reducedQ
    }
    return null
}

fun combineWeights(q: QExpr, ctx: CombineWeightsFixedPoint): QExpr = when(q) {
    // Flatten nested-weights.
    is WeightExpr -> {
        val c = q.child
        if (c is WeightExpr) {
            ctx.changed = true
            // Merge nested weights:
            WeightExpr(combineWeights(c.child, ctx), q.weight * c.weight)
        } else if (c is CombineExpr) {
            ctx.changed = true
            // Push weight down into Combine.
            CombineExpr(
                    c.children.map { combineWeights(it, ctx)},
                    c.weights.map { it * q.weight })
        } else {
            // Otherwise just recurse, no-changes:
            WeightExpr(combineWeights(q.child, ctx), q.weight)
        }
    }
    // Pull weights up into CombineExpr.
    is CombineExpr -> {
        // Statically-combine any now-redundant-children in CombineExpr:
        if (q.children.toSet().size < q.children.size) {
            ctx.combineRedundantCombineChildren(q)
        } else if (q.children.any { it is CombineExpr || it is WeightExpr }) {
            ctx.flattenCombineExpr(q)
        } else {
            CombineExpr(q.children.map { combineWeights(it, ctx) }, q.weights)
        }
    }

    is ConstBoolExpr,
    is ConstCountExpr,
    is ConstScoreExpr,
    is LengthsExpr,
    is LuceneExpr,
    is NeverMatchLeaf,
    is TextExpr -> q

    // recurse on all other expressions.
    is AbsoluteDiscountingQLExpr,
    is AlwaysMatchExpr,
    is AndExpr,
    is BM25Expr,
    is BoolToScoreExpr,
    is CountToBoolExpr,
    is CountToScoreExpr,
    is DirQLExpr,
    is MaxExpr,
    is MultExpr,
    is MultiExpr,
    is OrExpr,
    is OrderedWindowExpr,
    is ProxExpr,
    is RequireExpr,
    is SmallerCountExpr,
    is SynonymExpr,
    is UnorderedWindowCeilingExpr,
    is UnorderedWindowExpr,
    is WhitelistMatchExpr -> q.map { combineWeights(it, ctx) }
}

class TypeCheckError(msg: String): Exception(msg)

fun analyzeDataNeededRecursive(q: QExpr, needed: DataNeeded= DataNeeded.DOCS) {
    var childNeeds = needed
    childNeeds = when(q) {
        is TextExpr -> {
            if (childNeeds == DataNeeded.SCORES) {
                throw TypeCheckError("Cannot convert q=$q to score. Try DirQLExpr(q) or BM25Expr(q)")
            }
            q.needed = childNeeds
            childNeeds
        }
        is LengthsExpr -> return
        is AndExpr, is OrExpr -> DataNeeded.DOCS
    // Pass through whatever at this point.
        is WhitelistMatchExpr, is AlwaysMatchExpr, is NeverMatchLeaf, is MultiExpr -> childNeeds
        is LuceneExpr, is SynonymExpr -> childNeeds
        is WeightExpr, is CombineExpr, is MultExpr, is MaxExpr -> {
            DataNeeded.SCORES
        }
        is UnorderedWindowCeilingExpr, is SmallerCountExpr -> {
            if (q.children.size <= 1) {
                throw TypeCheckError("Need more than 1 child for an count summary Expr, e.g. $q")
            }
            DataNeeded.COUNTS
        }
        is ProxExpr, is UnorderedWindowExpr, is OrderedWindowExpr -> {
            if (q.children.size <= 1) {
                throw TypeCheckError("Need more than 1 child for an window Expr, e.g. $q")
            }
            DataNeeded.POSITIONS
        }
        is AbsoluteDiscountingQLExpr, is BM25Expr, is DirQLExpr ->  DataNeeded.COUNTS
        is CountToScoreExpr ->  DataNeeded.COUNTS
        is BoolToScoreExpr -> DataNeeded.DOCS
        is CountToBoolExpr -> DataNeeded.COUNTS
        is RequireExpr -> {
            analyzeDataNeededRecursive(q.cond, DataNeeded.DOCS)
            analyzeDataNeededRecursive(q.value, childNeeds)
            return
        }
        is ConstScoreExpr -> return assert(needed == DataNeeded.SCORES)
        is ConstCountExpr -> return assert(needed == DataNeeded.COUNTS || needed == DataNeeded.DOCS)
        is ConstBoolExpr -> return assert(needed == DataNeeded.DOCS)
    }
    q.children.forEach { analyzeDataNeededRecursive(it, childNeeds) }
}

fun applyEnvironment(env: RREnv, root: QExpr) {
    root.visit { q ->
        when(q) {
            is LuceneExpr -> q.parse(env as? IreneQueryLanguage ?: error("LuceneExpr in environment without LuceneParser."))
            is DirQLExpr -> if (q.mu == null) {
                q.mu = env.defaultDirichletMu
            }
            is AbsoluteDiscountingQLExpr -> if (q.delta == null) {
                q.delta = env.absoluteDiscountingDelta
            }
            is BM25Expr -> {
                if (q.b == null) q.b = env.defaultBM25b
                if (q.k == null) q.k = env.defaultBM25k
            }
            else -> q.applyEnvironment(env)
        }
    }
}

