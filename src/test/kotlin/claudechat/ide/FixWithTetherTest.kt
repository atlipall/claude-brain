package claudechat.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FixWithTetherTest : BasePlatformTestCase() {
    fun testOfferedOnAHighlightedProblem() {
        myFixture.configureByText("a.js", "function f( {\n  return 1;<caret>\n}\n")
        myFixture.doHighlighting()
        assertNotNull(myFixture.availableIntentions.firstOrNull { it.text == "Fix with Tether" })
    }

    fun testNotOfferedOnACleanLine() {
        myFixture.configureByText("b.js", "const <caret>a = 1;\nexport { a };\n")
        myFixture.doHighlighting()
        assertNull(myFixture.availableIntentions.firstOrNull { it.text == "Fix with Tether" })
    }
}
