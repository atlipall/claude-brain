package claudechat.ide.tools

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CompletableFuture

/** A fix [action] for [problem], to be tried with the caret at [offset]; [Fixes] keeps those available there. */
class FixCandidate(val problem: Problem, val offset: Int, val action: IntentionAction)

/**
 * Problems from an analysis the IDE's main highlighting passes don't run, such as the TypeScript service (whose errors
 * the editor shows from a separate pass). Registered by optional plugin dependencies.
 */
interface ProblemSource {
    /** What Claude is told failed, e.g. "the TypeScript service". */
    val name: String

    /** Starts checking [file]; null when this source does not handle it. Called on a pooled thread; must not block long. */
    fun start(project: Project, file: VirtualFile): CompletableFuture<List<Problem>>?

    /** The fixes this source offers for its problems on 1-based [line] of [file]. Pooled thread. */
    fun fixes(project: Project, file: VirtualFile, line: Int, deadline: Deadline): List<FixCandidate> = emptyList()

    companion object {
        val EP: ExtensionPointName<ProblemSource> = ExtensionPointName("local.claudechat.problemSource")
    }
}
