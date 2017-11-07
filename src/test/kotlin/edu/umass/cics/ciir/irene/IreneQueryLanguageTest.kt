package edu.umass.cics.ciir.irene

import org.junit.Assert
import org.junit.Test

/**
 * @author jfoley.
 */
class IreneQueryLanguageTest {
    val test = IreneQueryLanguage()
    @Test
    fun weightCombineTest() {
        val before = WeightExpr(WeightExpr(TextExpr("test"), 0.5), 2.0)
        val after = WeightExpr(TextExpr("test"), 1.0)
        Assert.assertEquals(after, test.simplify(before))
    }
    @Test
    fun manyWeightCombineTest() {
        val before = TextExpr("test").weighted(0.5).weighted(2.0).weighted(2.0).weighted(2.0)
        val after = WeightExpr(TextExpr("test"), 4.0)
        Assert.assertEquals(after, test.simplify(before))
    }
    @Test
    fun meanExprGivesEvenWeights() {
        val a = TextExpr("a")
        val b = TextExpr("b")

        val opt1 = test.simplify(MeanExpr(a, b))
        val opt2 = test.simplify(SumExpr(a.weighted(0.5), b.weighted(0.5)))
        val expected = CombineExpr(listOf(a, b), listOf(0.5, 0.5))
        Assert.assertEquals(expected, opt1)
        Assert.assertEquals(expected, opt2)
    }
    @Test
    fun complexSimplify() {
        val a = TextExpr("a")
        val b = TextExpr("b")
        val c = TextExpr("c")
        val input = MeanExpr(MeanExpr(a.weighted(2.0), b.weighted(3.0)), c)

        // mean(mean( 2*a, 3*b), c)
        // 0.5 * mean(2*a, 3*b) + 0.5 * c
        // 0.5 * 0.5 * 2.0 * a + 0.5 * 0.5 * 3 * b + 0.5 * c
        // 0.5 * a + 0.75 * b + 0.5 * c
        val out = test.simplify(input)

        Assert.assertEquals(CombineExpr(listOf(a,b,c), listOf(0.5, 0.75, 0.5)), out)
    }

    @Test
    fun testDataNeededAnd() {
        val a = TextExpr("a")
        val b = TextExpr("b")
        val c = TextExpr("c")

        val expr = AndExpr(listOf(a, b, c))
        analyzeDataNeededRecursive(expr, DataNeeded.DOCS)

        Assert.assertEquals(DataNeeded.DOCS, a.needed)
        Assert.assertEquals(DataNeeded.DOCS, b.needed)
        Assert.assertEquals(DataNeeded.DOCS, c.needed)
    }

    @Test
    fun testDataNeededScoreError() {
        try {
            analyzeDataNeededRecursive(MeanExpr(TextExpr("a"), TextExpr("b")))
            Assert.fail("Can't take the mean of a TextExpr!")
        } catch (e: TypeCheckError) {
            // PASS.
            Assert.assertNotNull(e)
        } catch (t: Throwable) {
            Assert.fail("Wrong kind of error thrown: $t")
        }
    }

    @Test
    fun testOrderedWindowNeeds() {
        val a = TextExpr("a")
        val b = TextExpr("b")
        val bigram = OrderedWindowExpr(listOf(a, b))
        analyzeDataNeededRecursive(bigram)
        Assert.assertEquals(DataNeeded.POSITIONS, a.needed)
        Assert.assertEquals(DataNeeded.POSITIONS, b.needed)

        // Calculation should not change if used in a score.
        val b2 = DirQLExpr(OrderedWindowExpr(listOf(a, b)))

        analyzeDataNeededRecursive(b2)
        Assert.assertEquals(DataNeeded.POSITIONS, a.needed)
        Assert.assertEquals(DataNeeded.POSITIONS, b.needed)
    }

    @Test
    fun testScorerNeeds() {
        val a = TextExpr("a")
        val b = TextExpr("b")
        val bigram = MeanExpr(listOf(DirQLExpr(a), BM25Expr(b)))
        analyzeDataNeededRecursive(bigram)
        Assert.assertEquals(DataNeeded.COUNTS, a.needed)
        Assert.assertEquals(DataNeeded.COUNTS, b.needed)
    }

}