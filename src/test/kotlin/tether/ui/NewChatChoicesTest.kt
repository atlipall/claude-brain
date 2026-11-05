package tether.ui

import tether.git.Worktree
import tether.session.ChatSpec
import tether.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class NewChatChoicesTest {
    private val main = Worktree("/p", "main", isMain = true)
    private val wt = Worktree("/p/.claude/worktrees/wt-1", "worktree-wt-1", isMain = false)
    private val detached = Worktree("/elsewhere/co", null, isMain = false)

    @Test fun withoutGitOffersProjectAndResumeOnly() = assertEquals(
        listOf(NewChatChoice.Open("In this project", ChatSpec.inProject("/p")), NewChatChoice.Resume, NewChatChoice.Archived),
        NewChatChoices.build("/p", null))

    @Test fun withGitOffersNewWorktreeAndEachLinkedWorktree() = assertEquals(
        listOf(
            NewChatChoice.Open("In this project", ChatSpec.inProject("/p")),
            NewChatChoice.NewWorktree,
            NewChatChoice.Open("In worktree wt-1 (worktree-wt-1)", ChatSpec("wt-1", "/p/.claude/worktrees/wt-1")),
            NewChatChoice.Open("In worktree co", ChatSpec("co", "/elsewhere/co")),
            NewChatChoice.Resume,
            NewChatChoice.Archived,
        ),
        NewChatChoices.build("/p", listOf(main, wt, detached)))

    @Test fun worktreeThatIsTheProjectItselfIsNotListed() = assertEquals(
        listOf(NewChatChoice.Open("In this project", ChatSpec.inProject("/p/wt")), NewChatChoice.NewWorktree, NewChatChoice.Resume, NewChatChoice.Archived),
        NewChatChoices.build("/p/wt", listOf(Worktree("/p", "main", true), Worktree("/p/wt", "feature", false))))

    @Test fun worktreeSpelledDifferentlyFromTheProjectIsNotListed() = assertEquals(
        listOf(NewChatChoice.Open("In this project", ChatSpec.inProject("/p/wt")), NewChatChoice.NewWorktree, NewChatChoice.Resume, NewChatChoice.Archived),
        NewChatChoices.build("/p/wt", listOf(Worktree("/p/wt/", "feature", false), Worktree("/p/./wt", "feature", false))))

    @Test fun openChatForFindsTheChatHoldingTheSession() {
        val open = listOf<Pair<String?, String>>(null to "a", "s1" to "b", "s2" to "c")
        assertEquals("c", NewChatChoices.openChatFor("s2", open))
        assertEquals(null, NewChatChoices.openChatFor("s3", open))
        assertEquals(null, NewChatChoices.openChatFor("s1", emptyList<Pair<String?, String>>()))
    }

    @Test fun resumeTextMarksSessionsOpenInATab() = assertEquals(
        "hello · main · T7 · open",
        NewChatChoices.resumeText(SessionSummary("s", "hello", 7, "/p"), "/p", isOpen = true) { "T$it" })

    @Test fun resumeTextShowsTitleFolderAndTime() {
        assertEquals(
            "fix bug · wt-1 · T5",
            NewChatChoices.resumeText(SessionSummary("s", "fix bug", 5, "/p/.claude/worktrees/wt-1"), "/p") { "T$it" })
        assertEquals("hello · main · T7", NewChatChoices.resumeText(SessionSummary("s", "hello", 7, "/p"), "/p") { "T$it" })
        assertEquals("Login fix · main · T7",
            NewChatChoices.resumeText(SessionSummary("s", "hello", 7, "/p", customTitle = "Login fix"), "/p") { "T$it" })
    }
}
