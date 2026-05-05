package com.github.jelenadjuric01.gitmuse.action

import com.github.jelenadjuric01.gitmuse.llm.LlmError
import com.github.jelenadjuric01.gitmuse.llm.LlmException
import com.github.jelenadjuric01.gitmuse.notification.Notifier
import com.github.jelenadjuric01.gitmuse.service.CommitMessageService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys

/**
 * Toolbar action shown in the Commit dialog message area.
 *
 * Flow:
 * 1. Verify there's a [CommitMessageI] in the data context (otherwise the action shouldn't have
 *    been enabled — but defensive).
 * 2. Run [CommitMessageService.generate] inside a [Task.Backgroundable] so the EDT stays free.
 * 3. On success: hop back to the EDT and call [CommitMessageI.setCommitMessage] — never auto-commit.
 * 4. On failure: hop to the EDT and surface a category-appropriate notification, with a
 *    "Configure…" link for problems the user can fix in settings.
 */
class GenerateCommitMessageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val service = project.service<CommitMessageService>()

        val task = object : Task.Backgroundable(project, "Generating commit message", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = service.generate()
                indicator.checkCanceled()
                result.fold(
                    onSuccess = { generation ->
                        invokeOnEdt(project) { control.setCommitMessage(generation.message) }
                    },
                    onFailure = { throwable ->
                        val error = (throwable as? LlmException)?.error
                            ?: LlmError.InvalidResponse("Unexpected error: ${throwable.javaClass.simpleName}")
                        val (text, withSettings) = describe(error)
                        invokeOnEdt(project) { Notifier.error(project, text, withSettings) }
                    },
                )
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun invokeOnEdt(project: Project, block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            block()
        }
    }

    /**
     * Maps each [LlmError] variant to user-facing notification text plus a flag indicating
     * whether the notification should include the "Configure…" remediation link.
     */
    private fun describe(error: LlmError): Pair<String, Boolean> = when (error) {
        LlmError.MissingApiKey ->
            "API key is not configured." to true
        LlmError.NoChanges ->
            "No staged changes to summarize." to false
        LlmError.Timeout ->
            "Request timed out — try again, or raise the timeout in Settings → Tools → Git Muse." to false
        is LlmError.Network ->
            "Network error: ${error.description}" to false
        is LlmError.HttpError -> when (error.status) {
            401, 403 ->
                "Authentication failed (HTTP ${error.status}). Check your API key." to true
            404 ->
                "Endpoint or model not found (HTTP 404). Verify Base URL and Model in settings." to true
            429 ->
                "Rate-limited by the provider (HTTP 429). Wait a moment and retry." to false
            in 500..599 -> {
                val tail = error.bodySnippet.ifBlank { "Try again later." }
                "Provider error (HTTP ${error.status}). $tail" to false
            }
            else ->
                "HTTP ${error.status}: ${error.bodySnippet}" to false
        }
        is LlmError.InvalidResponse ->
            error.detail to true
    }
}
