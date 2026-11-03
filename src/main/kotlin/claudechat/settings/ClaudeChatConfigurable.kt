package claudechat.settings

import claudechat.cli.PermissionMode
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class ClaudeChatConfigurable : BoundConfigurable("Claude Chat") {
    private val state get() = ClaudeChatSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row("Claude binary:") {
            textField()
                .bindText({ state.claudePath.orEmpty() }, { state.claudePath = it })
                .align(AlignX.FILL)
                .comment("Leave empty to auto-detect (PATH, ~/.local/bin, /opt/homebrew/bin, /usr/local/bin).")
        }
        row("Default permission mode:") {
            comboBox(listOf("") + PermissionMode.entries.map { it.cli })
                .bindItem({ state.defaultMode.orEmpty() }, { state.defaultMode = it.orEmpty() })
                .comment("Empty uses the CLI's own setting.")
        }
        row("Extra CLI arguments:") {
            textField()
                .bindText({ state.extraArgs.orEmpty() }, { state.extraArgs = it })
                .align(AlignX.FILL)
                .comment("For example: --model sonnet")
        }
    }
}
