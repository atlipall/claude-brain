package tether.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Color

/** IDE colours and font for the web view. */
object Theme {
    private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    fun message(): String {
        val font = UIUtil.getLabelFont()
        return buildJsonObject {
            put("type", "theme")
            put("bg", hex(UIUtil.getPanelBackground()))
            put("fg", hex(UIUtil.getLabelForeground()))
            put("muted", hex(UIUtil.getContextHelpForeground()))
            put("border", hex(JBColor.border()))
            put("accent", hex(JBUI.CurrentTheme.Link.Foreground.ENABLED))
            put("codeBg", hex(EditorColorsManager.getInstance().globalScheme.defaultBackground))
            put("font", font.family)
            put("fontSize", font.size)
            put("dark", !JBColor.isBright())
        }.toString()
    }
}
