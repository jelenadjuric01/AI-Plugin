package com.github.jelenadjuric01.gitmuse.service

import com.github.jelenadjuric01.gitmuse.settings.GitMuseSettings
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.io.StringWriter
import java.nio.file.Path

/**
 * Builds the unified-diff text fed to the LLM from the active changelist.
 *
 * Pipeline (all on a background thread, wrapped in a read action):
 * 1. Pull the default changelist's changes via [ChangeListManager].
 * 2. Skip binary files — sending non-text bytes to a chat completion is meaningless.
 * 3. Use IntelliJ's [IdeaTextPatchBuilder] + [UnifiedDiffWriter] to render real
 *    `git diff`-style output: per-file `--- a/path` / `+++ b/path` headers, and
 *    `@@ -X,Y +A,B @@` hunks containing only the changed lines plus a few lines of
 *    context. Far better signal-per-token than dumping full before/after content.
 *    Requires `bundledModule("intellij.platform.vcs.impl")` in `build.gradle.kts`.
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

        val basePath = Path.of(project.basePath ?: ".")
        val patches = IdeaTextPatchBuilder.buildPatch(
            project,
            changes,
            basePath,
            /* reverseAll = */ false,
            /* honorExcludedFromCommit = */ false,
        )
        if (patches.isEmpty()) return@compute DiffContext.Empty

        val writer = StringWriter()
        UnifiedDiffWriter.write(project, patches, writer, "\n", /* commitContext = */ null)
        val rawDiff = writer.toString()
        if (rawDiff.isBlank()) return@compute DiffContext.Empty

        val redacted = redactSecrets(rawDiff)
        val truncated = redacted.length > cap
        val finalText = if (truncated) {
            buildString {
                append(redacted, 0, cap)
                append("\n\n[truncated; ${redacted.length - cap} more characters omitted]")
            }
        } else {
            redacted
        }

        DiffContext.Present(text = finalText, fileCount = changes.size, truncated = truncated)
    }

    private fun isBinary(change: Change): Boolean {
        val rev = change.afterRevision ?: change.beforeRevision ?: return false
        return rev.file.fileType.isBinary
    }

    companion object {
        // Two best-effort detectors, applied in sequence. Group 1 in each preserves the
        // surrounding context (diff `+`/`-` prefix, YAML indentation, ...) so the
        // replacement only swaps the value.
        private val SECRET_PATTERNS: List<Regex> = listOf(
            // 1) `keyword: value`  /  `keyword=value` — most config-file shapes.
            Regex(
                """((?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth(?:orization)?[_-]?token|client[_-]?secret|password|secret|token)\s*[:=]\s*)['"]?[^\s'"]+""",
                RegexOption.MULTILINE,
            ),
            // 2) `Authorization: Bearer <token>` — HTTP auth header form. Distinct shape
            //    because the secret follows a *second* keyword (Bearer/Basic), not the colon.
            Regex(
                """((?i)authorization\s*:\s*(?:bearer|basic)\s+)['"]?[^\s'"]+""",
                RegexOption.MULTILINE,
            ),
        )

        /** Public for unit testing — replaces secret-shaped values with `***REDACTED***`. */
        internal fun redactSecrets(text: String): String =
            SECRET_PATTERNS.fold(text) { acc, pattern ->
                pattern.replace(acc) { match -> "${match.groupValues[1]}***REDACTED***" }
            }
    }
}
