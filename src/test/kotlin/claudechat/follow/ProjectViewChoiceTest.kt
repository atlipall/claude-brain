package claudechat.follow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectViewChoiceTest {
    @Test fun projectPaneFollows() = assertTrue(ProjectViewChoice.shouldSwitch("ProjectPane", null))

    @Test fun pluginScopeFollows() = assertTrue(ProjectViewChoice.shouldSwitch("Scope", "Claude: wt1"))

    @Test fun userScopeIsLeftAlone() {
        assertFalse(ProjectViewChoice.shouldSwitch("Scope", "Project Files"))
        assertFalse(ProjectViewChoice.shouldSwitch("Scope", null))
    }

    @Test fun otherPanesAreLeftAlone() {
        assertFalse(ProjectViewChoice.shouldSwitch("PackagesPane", null))
        assertFalse(ProjectViewChoice.shouldSwitch(null, null))
    }
}
