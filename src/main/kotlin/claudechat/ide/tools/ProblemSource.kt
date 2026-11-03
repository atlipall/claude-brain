package claudechat.ide.tools

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CompletableFuture

/**
 * Problems from an analysis the IDE's main highlighting passes don't run, such as the TypeScript service (whose errors
 * the editor shows from a separate pass). Registered by optional plugin dependencies.
 */
interface ProblemSource {
    /** What Claude is told failed, e.g. "the TypeScript service". */
    val name: String

    /** Starts checking [file]; null when this source does not handle it. Called on a pooled thread; must not block long. */
    fun start(project: Project, file: VirtualFile): CompletableFuture<List<Problem>>?

    companion object {
        val EP: ExtensionPointName<ProblemSource> = ExtensionPointName("local.claudechat.problemSource")
    }
}
