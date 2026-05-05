package com.github.jelenadjuric01.gitmuse.service

import com.github.jelenadjuric01.gitmuse.llm.GenerationResult
import com.github.jelenadjuric01.gitmuse.llm.LlmClient
import com.github.jelenadjuric01.gitmuse.llm.LlmError
import com.github.jelenadjuric01.gitmuse.llm.LlmException
import com.github.jelenadjuric01.gitmuse.llm.OpenAiCompatibleClient
import com.github.jelenadjuric01.gitmuse.llm.Prompt
import com.github.jelenadjuric01.gitmuse.settings.GitMuseSecrets
import com.github.jelenadjuric01.gitmuse.settings.GitMuseSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.time.Duration

/**
 * Project-scoped service that orchestrates: read settings → capture diff → call LLM → return.
 *
 * No state of its own. The action layer obtains an instance from the IntelliJ service container,
 * calls [generate], and surfaces the [Result] as a notification. Errors come back typed as
 * [LlmError] inside [Result.failure], so the action layer can route each variant to a
 * differently-worded notification.
 */
@Service(Service.Level.PROJECT)
class CommitMessageService(private val project: Project) {

    /**
     * Full pipeline. Must be called from a background thread.
     */
    fun generate(): Result<GenerationResult> {
        val state = GitMuseSettings.getInstance().state

        if (state.baseUrl.isBlank()) {
            return failure(
                LlmError.InvalidResponse(
                    "Base URL is not configured. Open Settings → Tools → Git Muse."
                )
            )
        }
        if (state.model.isBlank()) {
            return failure(
                LlmError.InvalidResponse(
                    "Model is not configured. Open Settings → Tools → Git Muse."
                )
            )
        }

        val diff = DiffContextBuilder(project).build(state.maxDiffChars)
        val present = when (diff) {
            DiffContext.Empty -> return failure(LlmError.NoChanges)
            is DiffContext.Present -> diff
        }

        val client = OpenAiCompatibleClient(
            baseUrl = state.baseUrl,
            model = state.model,
            apiKey = GitMuseSecrets.getApiKey(),
            requestTimeout = Duration.ofSeconds(state.requestTimeoutSeconds.toLong()),
        )

        return generateWith(client, present.text, branch = currentBranch())
    }

    /**
     * v1 returns null. Reading the current Git branch requires the Git4Idea plugin and a
     * `dynamic` dependency in plugin.xml — out of scope for the test task. The model
     * produces good messages from the diff alone; branch context is a nice-to-have, not load-bearing.
     */
    private fun currentBranch(): String? = null

    private fun <T> failure(error: LlmError): Result<T> = Result.failure(LlmException(error))

    companion object {
        /**
         * Test entry point — orchestration without IDE service lookups. Tests pass a
         * fake [LlmClient] and a hardcoded diff string; no [Project] needed.
         */
        fun generateWith(client: LlmClient, diff: String, branch: String?): Result<GenerationResult> =
            client.generate(Prompt.build(diff, branch))
    }
}
