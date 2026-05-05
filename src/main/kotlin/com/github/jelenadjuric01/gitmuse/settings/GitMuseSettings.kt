package com.github.jelenadjuric01.gitmuse.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level persisted settings for Git Muse.
 *
 * The API key is intentionally NOT a field here — it lives in PasswordSafe via [GitMuseSecrets]
 * to avoid being serialized to plaintext XML.
 */
@Service(Service.Level.APP)
@State(name = "GitMuseSettings", storages = [Storage("GitMuseSettings.xml")])
class GitMuseSettings : PersistentStateComponent<GitMuseSettings.State> {

    data class State(
        var baseUrl: String = "",
        var model: String = "",
        var maxDiffChars: Int = DEFAULT_MAX_DIFF_CHARS,
        var requestTimeoutSeconds: Int = DEFAULT_REQUEST_TIMEOUT_SECONDS,
    )

    private val state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    companion object {
        const val DEFAULT_MAX_DIFF_CHARS: Int = 12_000
        const val DEFAULT_REQUEST_TIMEOUT_SECONDS: Int = 30

        // Hard upper bound — protects against an accidentally huge diff being shipped
        // to the LLM regardless of user setting. Mirrored as the spinner's max in the UI.
        const val MAX_DIFF_CHARS_HARD_CAP: Int = 50_000

        fun getInstance(): GitMuseSettings =
            ApplicationManager.getApplication().getService(GitMuseSettings::class.java)
    }
}
