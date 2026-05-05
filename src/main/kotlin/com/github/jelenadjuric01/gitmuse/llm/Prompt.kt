package com.github.jelenadjuric01.gitmuse.llm

/**
 * Builds the chat-completion messages sent to the LLM.
 *
 * The system prompt is the single point where we constrain output format. Keep it tight —
 * every clause is one the model could otherwise drift on.
 */
object Prompt {

    val SYSTEM: String = """
        You generate Git commit messages from a unified diff using the Conventional Commits specification.

        How to read the diff (this is your only source of truth — do NOT invent changes that aren't shown):
        - File headers `--- a/<path>` and `+++ b/<path>` identify which file changed. `/dev/null` on either side means a new or deleted file.
        - `@@ -X,Y +A,B @@` markers separate hunks. Lines inside a hunk that start with `-` were removed, lines starting with `+` were added, and unprefixed lines are unchanged context shown for orientation.
        - Base your summary on what was actually added or removed. The unchanged context lines are NOT changes.

        Output format:
        - Header: `<type>(<optional scope>): <subject>`
          - `<type>` ∈ {feat, fix, refactor, docs, test, chore, perf, ci, build, style}.
          - `<scope>` is the most affected file path or module when one is clearly central; omit it if changes span many areas.
          - `<subject>` is imperative mood, lowercase, no trailing period, ≤72 characters.
        - Optional body, separated from the header by one blank line:
          - 1–3 short paragraphs.
          - Explain WHY the change was made or what observable behavior it produces. Do NOT restate the diff line-by-line — the reader can already see it.
          - Wrap body lines at 72 characters.

        Hard rules (violations are bugs):
        - Output ONLY the commit message. No code fences. No greeting, no preamble, no apology, no explanation about what you are about to write.
        - If the diff has a `[truncated …]` marker, summarize only the visible portion and acknowledge nothing about the omitted lines.
        - If the diff is trivial (formatting, comments, whitespace, imports reorder), prefer `type=style` or `type=chore`.
        - If multiple distinct changes are bundled, pick the dominant one for the header and mention the others in the body.
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
