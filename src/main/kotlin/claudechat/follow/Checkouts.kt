package claudechat.follow

import claudechat.git.canonicalPath

/**
 * A folder the IDE window can follow: the project itself ([MAIN]) or a `.claude/worktrees/<name>` folder in it.
 * [dir] is canonical.
 */
data class Checkout(val name: String, val dir: String) {
    val isMain: Boolean get() = name == MAIN

    /** The named scope the plugin keeps for this checkout. */
    val scopeName: String get() = SCOPE_PREFIX + name

    companion object {
        const val MAIN = "main"
        const val SCOPE_PREFIX = "Claude: "
    }
}

/** Where a chat's folder sits relative to this IDE window. */
sealed interface Placement {
    data class Followable(val checkout: Checkout) : Placement

    /** Anywhere else: another folder, or a worktree outside the project. [dir] is canonical. */
    data class Outside(val dir: String) : Placement
}

/** Maps folders and files to checkouts of the project at [projectDir]. Compares canonical paths. */
class CheckoutMapper(projectDir: String) {
    private val project = canonicalPath(projectDir)
    private val worktrees = "$project/$WORKTREES/"

    val main = Checkout(Checkout.MAIN, project)

    /** The project folder is main, `<project>/.claude/worktrees/<name>` is that worktree, anything else is outside. */
    fun place(cwd: String): Placement {
        val dir = canonicalPath(cwd)
        if (dir == project) return Placement.Followable(main)
        val name = dir.removePrefix(worktrees)
        return if (dir.startsWith(worktrees) && name.isNotEmpty() && '/' !in name && VALID_NAME.matches(name)) {
            Placement.Followable(Checkout(name, dir))
        } else {
            Placement.Outside(dir)
        }
    }

    /** [path] at the same relative path in [to], or null when [path] is not a file inside [from]. */
    fun counterpart(path: String, from: Checkout, to: Checkout): String? {
        val file = canonicalPath(path)
        val relative = file.removePrefix(from.dir.trimEnd('/') + "/")
        if (relative == file || relative.isEmpty()) return null
        // Main does not own the worktrees folder inside it.
        if (from.isMain && file.startsWith(worktrees)) return null
        return to.dir.trimEnd('/') + "/" + relative
    }

    companion object {
        const val WORKTREES = ".claude/worktrees"

        /** Folder names a scope pattern can hold without quoting (the names `claude --worktree` accepts). */
        private val VALID_NAME = Regex("[A-Za-z0-9._-]+")
    }
}

// Named-scope patterns, relative to the project content root. Checked against FilePatternPackageSet in build 262:
// "file:*" matches only top-level files and "file:*//*" only nested ones, so main needs both;
// "file:<dir>//*" matches every file at any depth under <dir> (and nothing under <dir>0).
// (Line comments: Kotlin block comments nest, so these patterns cannot appear in KDoc.)
object ScopePatterns {
    const val MAIN = "(file:*||file:*//*)&&!file:${CheckoutMapper.WORKTREES}//*"

    fun under(relativeDir: String): String = "file:${relativeDir.trim('/')}//*"

    fun forCheckout(checkout: Checkout): String =
        if (checkout.isMain) MAIN else under("${CheckoutMapper.WORKTREES}/${checkout.name}")
}
