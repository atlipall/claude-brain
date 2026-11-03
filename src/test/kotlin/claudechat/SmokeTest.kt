package claudechat

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SmokeTest : BasePlatformTestCase() {
    fun testLightProjectLoads() {
        assertNotNull(project.basePath)
    }
}
