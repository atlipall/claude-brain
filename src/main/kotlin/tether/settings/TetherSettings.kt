package tether.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
// Named from before the rename to Tether; changing it would drop saved settings.
@State(name = "ClaudeChatSettings", storages = [Storage("claudeChat.xml")])
class TetherSettings : SimplePersistentStateComponent<TetherSettings.State>(State()) {
    class State : BaseState() {
        /** Empty = auto-detect. */
        var claudePath by string("")
        /** Empty = whatever the CLI's own settings say. */
        var defaultMode by string("")
        var extraArgs by string("")
        /** The IDE window follows the selected chat's checkout (Project view scope, open editors, Find scope). */
        var followCheckout by property(true)
        /** Messages tell Claude which file, line, selection and function the editor shows (location only). */
        var attachEditorContext by property(true)
        /** Claude can call the IDE's diagnostics and find-usages tools (applies to chats started or restarted after). */
        var ideTools by property(true)
        /** After each of Claude's edits, the IDE checks the file and tells Claude about errors in it. */
        var checkEdits by property(true)
    }

    companion object {
        fun getInstance(): TetherSettings = service()
    }
}
