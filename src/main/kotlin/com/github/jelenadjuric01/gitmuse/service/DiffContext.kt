package com.github.jelenadjuric01.gitmuse.service

/**
 * Output of [DiffContextBuilder].
 *
 * [Empty] is the explicit "no usable changes" signal — distinct from a present-but-empty diff
 * (which can't actually happen, since the builder filters empty/binary entries upstream).
 */
sealed interface DiffContext {

    /** No staged changes, or every changed file was binary / unreadable. */
    data object Empty : DiffContext

    /**
     * @param text       the assembled unified-diff text, secret-redacted, possibly truncated.
     * @param fileCount  number of files included (after binary skip).
     * @param truncated  true if [text] was capped below the full diff's length.
     */
    data class Present(
        val text: String,
        val fileCount: Int,
        val truncated: Boolean,
    ) : DiffContext
}
