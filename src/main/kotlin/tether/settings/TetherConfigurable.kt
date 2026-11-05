package tether.settings

import tether.cli.PermissionMode
import tether.follow.CheckoutFollower
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class TetherConfigurable : BoundConfigurable("Tether") {
    private val state get() = TetherSettings.getInstance().state

    /** Saves the form and refreshes each open project's checkout follower; only projects whose chat tool window is open have one. */
    override fun apply() {
        super.apply()
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
        row("ACP agent command:") {
            textField()
                .bindText({ state.acpCommand.orEmpty() }, { state.acpCommand = it })
                .align(AlignX.FILL)
                .comment("An Agent Client Protocol agent that the message box offers besides Claude, e.g. hermes acp. Empty offers none. " +
                    "The agent keeps its own credentials, model and approval rules. IDE tools, edit checks, new worktrees and " +
                    "the past-session browser are for Claude only.")
        }
        row {
            checkBox("Attach editor context to messages")
                .bindSelected({ state.attachEditorContext }, { state.attachEditorContext = it })
                .comment("Tells Claude the file, caret line, selection and enclosing function of the editor you last used. Never the code itself.")
        }
        row {
            checkBox("Let Claude use IDE tools")
                .bindSelected({ state.ideTools }, { state.ideTools = it })
                .comment("Diagnostics and Find Usages, which never ask, plus quick fixes, Rename and Move, which ask like Claude's own edits. Applies to new or restarted chats.")
        }
        row {
            checkBox("Check Claude's edits for errors")
                .bindSelected({ state.checkEdits }, { state.checkEdits = it })
                .comment("After each edit the IDE checks the file and tells Claude about any errors, so it fixes them in the same turn. Applies to new or restarted chats.")
        }
    }
}
