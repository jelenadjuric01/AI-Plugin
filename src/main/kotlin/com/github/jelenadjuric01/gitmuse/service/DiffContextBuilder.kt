package com.github.jelenadjuric01.gitmuse.service

import com.github.jelenadjuric01.gitmuse.settings.GitMuseSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ContentRevision

/**
 * Builds the unified-diff text fed to the LLM from the active changelist.
 *
 * Pipeline (all on a background thread, wrapped in a read action):
 * 1. Pull the default changelist's changes via [ChangeListManager].
 * 2. Skip binary files — sending non-text bytes to a chat completion is meaningless.
 * 3. For each remaining [Change], emit a unified-diff-flavored block:
 *    - new file:     `--- /dev/null` / `+++ b/path` followed by every line prefixed `+`
 *    - deleted file: `--- a/path`  / `+++ /dev/null` followed by every line prefixed `-`
 *    - modified file: both headers, then full before content prefixed `-` and full after
 *      content prefixed `+`. This is lossier (token-wise) than a real LCS-aligned hunk
 *      output but it's reliable, dependency-free, and the truncation cap below contains
 *      the worst case.
 * 4. Run secret-pattern redaction. Best-effort — replaces values for keys whose names look
 *    secret-shaped (`api_key`, `password`, `token`, `Bearer`, ...).
 * 5. Truncate at the lesser of [maxChars] and [GitMuseSettings.MAX_DIFF_CHARS_HARD_CAP];
 *    append a `[truncated; …]` marker so the prompt explicitly tells the model not to
 *    speculate about the omitted portion.
 */
class DiffContextBuilder(private val project: Project) {

    fun build(maxChars: Int): DiffContext = ReadAction.compute<DiffContext, RuntimeException> {
        val cap = maxChars.coerceAtMost(GitMuseSettings.MAX_DIFF_CHARS_HARD_CAP)
        val changes = ChangeListManager.getInstance(project).defaultChangeList.changes
            .filterNot { isBinary(it) }
        if (changes.isEmpty()) return@compute DiffContext.Empty

        val basePath = project.basePath
        val rendered = changes.mapNotNull { renderChange(it, basePath) }
        if (rendered.isEmpty()) return@compute DiffContext.Empty

        val joined = rendered.joinToString(separator = "\n\n")
        val redacted = redactSecrets(joined)
        val truncated = redacted.length > cap
        val finalText = if (truncated) {
            buildString {
                append(redacted, 0, cap)
                append("\n\n[truncated; ${redacted.length - cap} more characters omitted]")
            }
        } else {
            redacted
        }

        DiffContext.Present(text = finalText, fileCount = rendered.size, truncated = truncated)
    }

    private fun isBinary(change: Change): Boolean {
        val rev = change.afterRevision ?: change.beforeRevision ?: return false
        return rev.file.fileType.isBinary
    }

    private fun renderChange(change: Change, basePath: String?): String? {
        val before = change.beforeRevision
        val after = change.afterRevision
        val rev = after ?: before ?: return null
        val relPath = rev.file.path.relativeTo(basePath)

        val sb = StringBuilder()
        when {
            before == null && after != null -> {
                sb.append("--- /dev/null\n")
                sb.append("+++ b/").append(relPath).append('\n')
                appendWithPrefix(sb, after.contentOrNull(), '+')
            }
            before != null && after == null -> {
                sb.append("--- a/").append(relPath).append('\n')
                sb.append("+++ /dev/null\n")
                appendWithPrefix(sb, before.contentOrNull(), '-')
            }
            before != null && after != null -> {
                sb.append("--- a/").append(relPath).append('\n')
                sb.append("+++ b/").append(relPath).append('\n')
                appendWithPrefix(sb, before.contentOrNull(), '-')
                appendWithPrefix(sb, after.contentOrNull(), '+')
            }
            else -> return null
        }
        return sb.toString().trimEnd()
    }

    private fun appendWithPrefix(sb: StringBuilder, content: String?, prefix: Char) {
        if (content.isNullOrEmpty()) return
        content.lineSequence().forEach { line ->
            sb.append(prefix).append(line).append('\n')
        }
    }

    private fun ContentRevision.contentOrNull(): String? = try {
        content
    } catch (e: VcsException) {
        null
    }

    private fun String.relativeTo(basePath: String?): String =
        if (basePath != null && startsWith(basePath)) {
            removePrefix(basePath).trimStart('/')
        } else {
            this
        }

    companion object {
        // Best-effort secret-shape detector. Captures key + delimiter as group 1 so the
        // replacement preserves whatever leading context (unified-diff `+`/`-` prefix,
        // YAML indentation, etc.) was on the line.
        private val SECRET_PATTERN = Regex(
            """((?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth(?:orization)?[_-]?token|client[_-]?secret|password|secret|token|bearer)\s*[:=]\s*)['"]?[^\s'"]+""",
            RegexOption.MULTILINE,
        )

        /** Public for unit testing — replaces secret-shaped values with `***REDACTED***`. */
        internal fun redactSecrets(text: String): String =
            SECRET_PATTERN.replace(text) { match -> "${match.groupValues[1]}***REDACTED***" }
    }
}
