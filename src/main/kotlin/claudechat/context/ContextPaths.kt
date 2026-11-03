package claudechat.context

import claudechat.follow.CheckoutMapper
import claudechat.follow.Placement
import claudechat.git.canonicalPath

object ContextPaths {
    /**
     * [file] as the chat working in [cwd] names it, comparing canonical paths. When the chat's folder is a checkout of
     * the project at [projectDir] and [file] lies in another checkout, the answer is the file's counterpart in the
     * chat's checkout, if [exists] says it is there. Otherwise it is [file] relative to the chat's folder. Null when
     * neither applies, including a worktree file with no counterpart seen from main (main does not own
     * `.claude/worktrees`, as in v1's edit notes).
     */
    fun forChat(file: String, cwd: String, projectDir: String, exists: (String) -> Boolean): String? {
        val mapper = CheckoutMapper(projectDir)
        val path = canonicalPath(file)
        val chat = (mapper.place(cwd) as? Placement.Followable)?.checkout
        val home = chat?.let { mapper.checkoutOf(path) }
        if (chat != null && home != null && home != chat) {
            return mapper.counterpart(path, home, chat)?.takeIf(exists)?.let { relativeTo(it, chat.dir) }
        }
        return relativeTo(path, chat?.dir ?: canonicalPath(cwd))
    }

    private fun relativeTo(path: String, dir: String): String? =
        path.removePrefix(dir.trimEnd('/') + "/").takeIf { it != path && it.isNotEmpty() }
}
