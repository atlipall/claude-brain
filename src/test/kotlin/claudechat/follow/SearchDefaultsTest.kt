package claudechat.follow

import com.intellij.find.FindManager
import com.intellij.find.FindSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SearchDefaultsTest : BasePlatformTestCase() {
    private var savedScopeName: String? = null

    override fun setUp() {
        super.setUp()
        savedScopeName = FindSettings.getInstance().defaultScopeName // application-wide
    }

    override fun tearDown() {
        try { FindSettings.getInstance().defaultScopeName = savedScopeName } finally { super.tearDown() }
    }

    fun testFindInFilesDefaultsToTheScope() {
        FindManager.getInstance(project).findInProjectModel.directoryName = "/somewhere"
        SearchDefaults.use(project, "Claude: wt1")
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
        val model = FindManager.getInstance(project).findInProjectModel
        assertTrue(model.isCustomScope)
        assertFalse(model.isProjectScope)
        assertNull(model.directoryName)
        assertNull(model.moduleName)
        assertEquals("Claude: wt1", model.customScopeName)
    }
}
