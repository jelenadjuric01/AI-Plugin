package com.github.jelenadjuric01.gitmuse.llm

/**
 * Builds the chat-completion messages sent to the LLM.
 *
 * The system prompt is the single point where we constrain output format. Keep it tight —
 * every clause is one the model could otherwise drift on.
 */
object Prompt {

    val SYSTEM: String = """
        You write Git commit messages from a unified diff using the Conventional Commits specification.

        Output ONLY the commit message text. Do NOT wrap it in code fences. Do NOT add any prose, explanation, greeting, or apology before or after.

        Format:
        - First line (header): <type>(<optional scope>): <subject>
          - <type> is one of: feat, fix, refactor, docs, test, chore, perf, ci, build, style
          - <subject> is imperative mood, lowercase, no trailing period, no more than 72 characters.
        - If a body is needed, leave one blank line after the header, then write 1–3 short paragraphs explaining WHY the change was made — not WHAT, since the diff already shows that.

        Constraints:
        - Wrap body lines at 72 characters.
        - If the diff was truncated (look for a "[truncated …]" marker), focus on the visible portion and do not speculate about the rest.
        - If the diff is trivial (e.g. only formatting), prefer type=style or type=chore.
    """.trimIndent()

    /**
     * @param diff   the (possibly truncated) unified diff of staged changes.
     * @param branch current branch name, when known — useful context the model can fold into scope.
     */
    fun build(diff: String, branch: String?): List<ChatMessage> {
        val user = buildString {
            if (!branch.isNullOrBlank()) {
                append("Current branch: ").appendLine(branch)
                appendLine()
            }
            appendLine("Diff:")
            append(diff)
        }
        return listOf(
            ChatMessage(role = "system", content = SYSTEM),
            ChatMessage(role = "user", content = user),
        )
    }
}
