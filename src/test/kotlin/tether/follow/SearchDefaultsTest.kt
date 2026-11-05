package tether.follow

import com.intellij.find.FindManager
import com.intellij.find.FindSettings
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import tether.settings.TetherSettings

class SearchDefaultsTest : BasePlatformTestCase() {
    private var savedScopeName: String? = null

    /** Remembers the application-wide default scope name, which [tearDown] puts back. */
    override fun setUp() {
        super.setUp()
        SearchDefaults.forgetSaved()
        savedScopeName = FindSettings.getInstance().defaultScopeName
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

    fun testRestoreBringsBackTheUsersDefaultOnce() {
        FindSettings.getInstance().defaultScopeName = "Mine"
        SearchDefaults.use(project, "Claude: wt1")
        SearchDefaults.use(project, "Claude: wt2")
        SearchDefaults.restore(project)
        assertEquals("the second switch must not overwrite the remembered value", "Mine", FindSettings.getInstance().defaultScopeName)
        FindSettings.getInstance().defaultScopeName = "Other"
        SearchDefaults.restore(project)
        assertEquals("Other", FindSettings.getInstance().defaultScopeName)
    }

    fun testTheUsersDefaultIsKeptInTheSettingsSoACrashDoesNotLoseIt() {
        FindSettings.getInstance().defaultScopeName = "Mine"
        SearchDefaults.use(project, "Claude: wt1")
        assertEquals("Mine", TetherSettings.getInstance().state.savedFindScope)
        assertTrue(TetherSettings.getInstance().state.findScopeSaved)
    }

    fun testAScopeLeftByACrashIsPutBackByTheNextProjectToStart() {
        TetherSettings.getInstance().state.apply { findScopeSaved = true; savedFindScope = "Mine" }
        FindSettings.getInstance().defaultScopeName = "Claude: wt1"
        SearchDefaults.restore(project)
        assertEquals("Mine", FindSettings.getInstance().defaultScopeName)
        assertFalse(TetherSettings.getInstance().state.findScopeSaved)
    }

    fun testOnlyTheProjectThatSetTheScopePutsTheDefaultBack() {
        FindSettings.getInstance().defaultScopeName = "Mine"
        SearchDefaults.use(project, "Claude: wt1")
        SearchDefaults.restore(ProjectManager.getInstance().defaultProject)
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
        SearchDefaults.restore(project)
        assertEquals("Mine", FindSettings.getInstance().defaultScopeName)
    }

    fun testRestoreLeavesAScopeTheUserPickedLater() {
        FindSettings.getInstance().defaultScopeName = "Mine"
        SearchDefaults.use(project, "Claude: wt1")
        FindSettings.getInstance().defaultScopeName = "Changed"
        SearchDefaults.restore(project)
        assertEquals("Changed", FindSettings.getInstance().defaultScopeName)
    }
}
