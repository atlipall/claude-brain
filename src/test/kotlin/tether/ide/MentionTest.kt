package tether.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class MentionTest {
    @Test fun noSelection() = assertEquals("@src/a.ts", Mention.format("src/a.ts", null, null))
    @Test fun singleLine() = assertEquals("@src/a.ts#L5", Mention.format("src/a.ts", 5, 5))
    @Test fun range() = assertEquals("@src/a.ts#L5-9", Mention.format("src/a.ts", 5, 9))

    @Test fun relativeToFolder() {
        assertEquals("src/a.ts", Mention.relativeTo("/p/.claude/worktrees/wt-1/src/a.ts", "/p/.claude/worktrees/wt-1"))
        assertEquals(".claude/worktrees/wt-1/a.ts", Mention.relativeTo("/p/.claude/worktrees/wt-1/a.ts", "/p/"))
        assertEquals(null, Mention.relativeTo("/q/a.ts", "/p"))
        assertEquals(null, Mention.relativeTo("/pp/a.ts", "/p"))
    }
}
