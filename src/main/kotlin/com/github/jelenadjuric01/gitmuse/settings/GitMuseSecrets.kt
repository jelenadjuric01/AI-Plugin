package com.github.jelenadjuric01.gitmuse.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Sole entry point for reading/writing the Git Muse API key.
 *
 * Backed by IntelliJ's [PasswordSafe] (system keychain on macOS, Credential Manager on Windows,
 * KWallet/libsecret on Linux). Never logs the key; never returns it in any error path.
 */
object GitMuseSecrets {

    private const val SUBSYSTEM = "Git Muse"
    private const val KEY_NAME = "API_KEY"

    private val attributes: CredentialAttributes
        get() = CredentialAttributes(generateServiceName(SUBSYSTEM, KEY_NAME))

    fun getApiKey(): String? =
        PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }

    fun setApiKey(value: String) {
        require(value.isNotBlank()) { "API key must not be blank — call clear() to remove." }
        PasswordSafe.instance.set(attributes, Credentials("apiKey", value))
    }

    fun clear() {
        PasswordSafe.instance.set(attributes, null)
    }
}
