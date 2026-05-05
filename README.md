# GitMuse

![Build](https://github.com/jelenadjuric01/GitMuse/workflows/Build/badge.svg)

An IntelliJ plugin that generates [Conventional Commits](https://www.conventionalcommits.org/) messages from your staged diff using an LLM.

GitMuse adds a button to the IDE's Commit dialog. Click it and a clear, format-correct commit message appears in the message field for you to review and edit before committing. The plugin never commits for you, never fires unprompted, and never blocks the IDE while it waits for the model.

## Quick start

1. Install the plugin (during development: `./gradlew runIde` opens a sandbox IDE with it pre-loaded).
2. Open `Settings → Tools → Git Muse` and configure one of the following:

   | Provider | `Base URL` | Example `Model` | API key |
   | --- | --- | --- | --- |
   | **Groq** (free tier — recommended) | `https://api.groq.com/openai/v1` | `llama-3.1-8b-instant` | [console.groq.com/keys](https://console.groq.com/keys) |
   | **Ollama** (local, no key) | `http://localhost:11434/v1` | `llama3.1:8b` | leave blank |
   | **OpenAI** | `https://api.openai.com/v1` | `gpt-4o-mini` | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) |

   The API key is stored via IntelliJ's `PasswordSafe` — never in plaintext config.
3. Make some changes, open the Commit dialog (`Cmd-K` / `Ctrl-K`), click the **Generate AI Commit Message** button in the message toolbar.

## How it works

When you click the button, GitMuse:

1. Reads the diff of your active changelist via IntelliJ's VCS APIs (skipping binaries, redacting obvious secrets, and capping total size).
2. Wraps it in a chat-completion prompt that constrains the model to Conventional Commits format.
3. Sends it to your configured `baseUrl` over HTTPS, off the EDT.
4. Writes the response into the commit message field via `VcsDataKeys.COMMIT_MESSAGE_CONTROL` — never auto-committing.

Errors (missing key, auth failure, network, no staged changes) are surfaced as IDE notifications, not modal dialogs.

## Development

```bash
./gradlew runIde            # launch a sandbox IDE with the plugin loaded
./gradlew test              # unit tests
./gradlew check             # tests + verifications
./gradlew verifyPlugin      # JetBrains Plugin Verifier
./gradlew buildPlugin       # produce distributable .zip in build/distributions/
```

JDK 21 required.

---
Plugin scaffolded from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
