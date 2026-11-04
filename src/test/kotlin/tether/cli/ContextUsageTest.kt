package tether.cli

import tether.testutil.Fx
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextUsageTest {
    private fun reply(line: String): JsonObject = parseJsonObject(line)!!.obj("response")!!.obj("response")!!

    @Test fun parsesTheCliReply() {
        val usage = ContextUsage.parse(reply(Fx.contextResponse("c1")))!!
        assertEquals(14851L, usage.used)
        assertEquals(1000000L, usage.max)
        assertEquals(1, usage.percent)
        assertEquals(96, usage.autocompactAt) // (1M - 33k) / 1M
        // Only "used" categories with tokens: deferred tools, the buffer, free space and empty ones are left out.
        assertEquals(
            listOf("System prompt" to 2495L, "System tools" to 646L, "Skills" to 9924L, "Messages" to 1290L),
            usage.categories.map { it.name to it.tokens },
        )
    }

    @Test fun percentageFallsBackToTheTokenCounts() {
        val usage = ContextUsage.parse(parseJsonObject("""{"totalTokens":50000,"maxTokens":200000}""")!!)!!
        assertEquals(25, usage.percent)
        assertNull(usage.autocompactAt)
        assertEquals(emptyList<ContextCategory>(), usage.categories)
    }

    @Test fun fractionalPercentageIsRounded() =
        assertEquals(38, ContextUsage.parse(parseJsonObject("""{"totalTokens":5,"maxTokens":10,"percentage":37.6}""")!!)!!.percent)

    @Test fun percentIsClamped() =
        assertEquals(100, ContextUsage.parse(parseJsonObject("""{"totalTokens":5,"maxTokens":10,"percentage":140}""")!!)!!.percent)

    @Test fun missingOrBadCountsGiveNull() {
        assertNull(ContextUsage.parse(parseJsonObject("""{"maxTokens":10}""")!!))
        assertNull(ContextUsage.parse(parseJsonObject("""{"totalTokens":5,"maxTokens":0}""")!!))
    }
}
