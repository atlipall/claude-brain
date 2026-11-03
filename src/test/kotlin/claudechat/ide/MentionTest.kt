package claudechat.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class MentionTest {
    @Test fun noSelection() = assertEquals("@src/a.ts", Mention.format("src/a.ts", null, null))
    @Test fun singleLine() = assertEquals("@src/a.ts#L5", Mention.format("src/a.ts", 5, 5))
    @Test fun range() = assertEquals("@src/a.ts#L5-9", Mention.format("src/a.ts", 5, 9))
}
