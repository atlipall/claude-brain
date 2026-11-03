package claudechat.settings

import claudechat.cli.PermissionMode
import claudechat.follow.CheckoutFollower
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class ClaudeChatConfigurable : BoundConfigurable("Claude Chat") {
    private val state get() = ClaudeChatSettings.getInstance().state

    override fun apply() {
        super.apply()
        // Only projects whose chat tool window is open have a follower and chats.
        ProjectManager.getInstance().openProjects.forEach { project ->
            project.serviceIfCreated<CheckoutFollower>()?.refresh()
        }
    }

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
        row {
            checkBox("IDE follows the selected chat's checkout")
                .bindSelected({ state.followCheckout }, { state.followCheckout = it })
                .comment("Selecting a worktree chat scopes the Project view and Find in Files to that worktree and swaps open editors to its files.")
        }
        row {
            checkBox("Attach editor context to messages")
                .bindSelected({ state.attachEditorContext }, { state.attachEditorContext = it })
                .comment("Tells Claude the file, caret line, selection and enclosing function of the editor you last used. Never the code itself.")
        }
    }
}
