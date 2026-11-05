package tether.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PromptHistoryTest : BasePlatformTestCase() {
    private val history get() = PromptHistory.getInstance(project)

    /** Project services persist between tests, so the history starts empty. */
    override fun setUp() {
        super.setUp()
        history.clear()
    }

    fun testAppendKeepsOrderAndSkipsImmediateRepeat() = assertEquals(
        listOf("a", "b", "a"),
        listOf("a", "b", "b", "a").fold(emptyList<String>()) { acc, t -> PromptHistory.append(acc, t) })

    fun testAppendIgnoresBlank() = assertEquals(listOf("a"), PromptHistory.append(listOf("a"), "  "))

    fun testAppendCapsAtOneHundredDroppingOldest() {
        val next = PromptHistory.append((1..100).map { "m$it" }, "m101")
        assertEquals(100, next.size)
        assertEquals("m2", next.first())
        assertEquals("m101", next.last())
    }

    fun testAddStoresInStateAndNotifiesSubscribers() {
        val seen = mutableListOf<List<String>>()
        val unsubscribe = history.subscribe { seen += it }
        history.add("one")
        history.add("one")
        history.add("two")
        unsubscribe()
        history.add("three")
        assertEquals(listOf("one", "two", "three"), history.entries())
        assertEquals(listOf(listOf("one"), listOf("one", "two")), seen)
        assertEquals(listOf("one", "two", "three"), history.state.entries.toList())
    }

    fun testLoadStateRestoresEntries() {
        history.loadState(PromptHistory.State().apply { entries = mutableListOf("x", "y") })
        assertEquals(listOf("x", "y"), history.entries())
    }
}
