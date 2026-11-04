package tether.ide

import tether.context.ContextPaths
import tether.ide.tools.Problem
import tether.ide.tools.ProblemReport
import tether.session.Chat
import tether.session.Chats
import tether.ui.ChatTabs
import tether.ui.ChatToolWindowFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefApp
import java.io.File

/** The chat the IDE's "… Tether" actions talk to. */
object TetherTarget {
    /** Shows the Tether window and calls [use] with its selected chat, opening a main chat when there is none. EDT. */
    fun withChat(project: Project, use: (Chat) -> Unit) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.ID) ?: return
        toolWindow.activate {
            val tabs = ChatTabs(project, toolWindow)
            val chat = tabs.selectedChat()
                ?: if (JBCefApp.isSupported()) tabs.open(Chats.getInstance(project).mainSpec()) else return@activate
            use(chat)
        }
    }

    /**
     * [file] as [chat] names it: relative to its folder (Claude resolves paths there), a main file mapped to its copy
     * in a worktree chat's checkout; else relative to the content root.
     */
    fun pathFor(project: Project, chat: Chat, file: VirtualFile): String =
        ContextPaths.forChat(file.path, chat.cwd, Chats.getInstance(project).projectDir) { File(it).exists() }
            ?: ProjectFileIndex.getInstance(project).getContentRootForFile(file)?.let { VfsUtilCore.getRelativePath(file, it) }
            ?: file.path
}

/** A failed test as the test runner shows it. */
data class FailedTest(val name: String, val location: String?, val message: String?, val stacktrace: String?)

/** What the IDE's "… Tether" actions put in the chat. */
object TetherMessages {
    private const val MAX_TESTS = 10
    private const val MAX_TRACE_LINES = 40
    private const val MAX_MESSAGE = 2000
    const val MAX_CONSOLE = 20_000

    /** Asks Claude to fix [problems] (worst first) on [line] of [path]. */
    fun fix(path: String, line: Int, problems: List<Problem>): String {
        val what = when {
            problems.size > 1 -> "these problems"
            else -> "this ${problems.single().severity.label}"
        }
        return "Fix $what at @$path#L$line:\n" + problems.joinToString("\n") { "- ${it.severity.label}: ${ProblemReport.oneLine(it.message)}" }
    }

    /** The failures of [tests], for Claude to look into; the message box gets it, so the user can add what they want. */
    fun failedTests(tests: List<FailedTest>): String = buildString {
        append(if (tests.size == 1) "This test failed:" else "These ${tests.size} tests failed:")
        for (t in tests.take(MAX_TESTS)) {
            append("\n\n").append(t.name)
            t.location?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            val body = listOfNotNull(
                t.message?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_MESSAGE) it.take(MAX_MESSAGE) + "…" else it },
                t.stacktrace?.trim()?.takeIf { it.isNotEmpty() }?.lines()?.let { lines ->
                    if (lines.size > MAX_TRACE_LINES) (lines.take(MAX_TRACE_LINES) + "…").joinToString("\n") else lines.joinToString("\n")
                },
            ).distinct().joinToString("\n")
            if (body.isNotEmpty()) append("\n```\n").append(fenceSafe(body)).append("\n```")
        }
        if (tests.size > MAX_TESTS) append("\n\n… and ${tests.size - MAX_TESTS} more.")
    }

    /** Console output (its end, when long). */
    fun console(text: String): String {
        val trimmed = text.trimEnd()
        val shown = if (trimmed.length > MAX_CONSOLE) "…" + trimmed.takeLast(MAX_CONSOLE) else trimmed
        return "Console output:\n```\n${fenceSafe(shown)}\n```"
    }

    /** Output that itself contains ``` would end the block early. */
    private fun fenceSafe(text: String) = text.replace("```", "`​``")
}
