package com.github.jelenadjuric01.gitmuse.settings

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Pure Swing form for Git Muse settings.
 *
 * Holds the input components but no business logic — [GitMuseSettingsConfigurable] reads/writes
 * values to/from [GitMuseSettings] and [GitMuseSecrets].
 */
class GitMuseSettingsComponent {

    val baseUrlField: JBTextField = JBTextField()
    val modelField: JBTextField = JBTextField()
    val apiKeyField: JBPasswordField = JBPasswordField()
    val maxDiffCharsSpinner: JSpinner = JSpinner(
        SpinnerNumberModel(
            GitMuseSettings.DEFAULT_MAX_DIFF_CHARS,
            500,
            GitMuseSettings.MAX_DIFF_CHARS_HARD_CAP,
            500,
        )
    )
    val timeoutSpinner: JSpinner = JSpinner(
        SpinnerNumberModel(GitMuseSettings.DEFAULT_REQUEST_TIMEOUT_SECONDS, 5, 300, 5)
    )

    val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Base URL:", baseUrlField, 1, false)
        .addComponent(hint("e.g. <code>https://api.groq.com/openai/v1</code>"))
        .addLabeledComponent("Model:", modelField, 1, false)
        .addComponent(
            hint(
                "e.g. <code>llama-3.1-8b-instant</code> (Groq), " +
                    "<code>llama3.1:8b</code> (Ollama), <code>gpt-4o-mini</code> (OpenAI)"
            )
        )
        .addLabeledComponent("API key:", apiKeyField, 1, false)
        .addComponent(
            hint(
                "Stored via IntelliJ's PasswordSafe. " +
                    "Enter to set or replace; leave blank to keep the existing key."
            )
        )
        .addLabeledComponent("Max diff characters:", maxDiffCharsSpinner, 1, false)
        .addLabeledComponent("Request timeout (seconds):", timeoutSpinner, 1, false)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    val preferredFocusedComponent: JComponent get() = baseUrlField

    private fun hint(html: String): JBLabel = JBLabel("<html><i>$html</i></html>").apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(0, 0, 6, 0)
    }
}
