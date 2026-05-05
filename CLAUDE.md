# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Status

This repo is **GitMuse** — an IntelliJ plugin that generates Conventional Commits messages from the staged diff using an LLM. It was scaffolded from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) and renamed; the template's example classes (`MyToolWindowFactory`, `MyProjectActivity`, `MyProjectService`, `MyPluginTest`) have been removed. Only `GitMuseBundle.kt` remains as the i18n bundle scaffold.

The implementation plan lives in `PLAN.md` at the repo root and is the source of truth for the architecture, phasing, and decisions.

## Build & Run

JDK 21 is required (CI uses Zulu 21). The Gradle wrapper is checked in.

```bash
./gradlew runIde            # launch a sandbox IDE with the plugin loaded
./gradlew test              # unit tests only
./gradlew check             # tests + all verifications
./gradlew verifyPlugin      # JetBrains Plugin Verifier (compatibility checks)
./gradlew buildPlugin       # produce distributable .zip in build/distributions/
./gradlew patchChangelog    # sync CHANGELOG.md "Unreleased" section
```

Run a single test once tests exist (substitute the actual class):
```bash
./gradlew test --tests "com.github.jelenadjuric01.gitmuse.llm.PromptTest"
```

The same actions are pre-wired as IntelliJ run configurations under `.run/` (`Run Plugin`, `Run Tests`, `Run Verifications`).

## Tech Stack Pinning

Versions live in `settings.gradle.kts` and `build.gradle.kts` and matter:
- Kotlin **2.1.20** (JVM) — both the language plugin and `kotlin.plugin.serialization`.
- IntelliJ Platform plugin **2.16.0** targeting **IntelliJ IDEA 2025.2.6.2**.
- JUnit **4.13.2** (not JUnit 5 — the IntelliJ test framework expects JUnit 4).
- `kotlinx-serialization-json` **1.7.3** (must align with Kotlin 2.1.20).
- `org.jetbrains.changelog` 2.5.0.

## Non-Obvious Configuration

- **`kotlin.stdlib.default.dependency = false`** in `gradle.properties` is deliberate. IntelliJ bundles its own Kotlin stdlib, so adding another causes runtime `ClassLoader` conflicts. Don't "fix" this by re-adding the stdlib dependency.
- **No OkHttp dependency.** HTTP is via the JDK 21 `java.net.http.HttpClient`. The IntelliJ Platform bundles its own OkHttp, and adding our own creates `NoSuchMethodError` risk on platform updates. If you reach for OkHttp, stop and use the JDK client.
- **No explicit `kotlinx-coroutines-core` dependency.** The platform bundles it; declaring our own pulls a version skew. The plugin uses `Task.Backgroundable` (no coroutines) anyway.
- **Configuration Cache is on** (`org.gradle.configuration-cache = true`). If a Gradle task fails with a configuration-cache-related error, retry with `--no-configuration-cache` and treat that as a bug to fix in the task — don't disable the cache project-wide.
- Plugin metadata: `<id>` is `com.github.jelenadjuric01.gitmuse`, `<name>` is `Git Muse`, `<resource-bundle>` is `messages.GitMuseBundle`. Don't drift these.

## Testing Conventions

Tests for code that touches **PSI, VFS, services, or actions** extend `BasePlatformTestCase` (slow but real-IDE fixture). Method names follow JUnit 4: `fun testXxx()` — the platform runner discovers them by `test` prefix; backtick-quoted names won't be picked up by that runner.

For everything else (pure logic, prompt building, HTTP-client mapping, formatting, error mapping), use plain JUnit 4 — no `BasePlatformTestCase`. Startup is seconds faster and there's no value in the IDE fixture for non-IDE-glue code. The `LlmClient` interface is the test seam; `FakeLlmClient` substitutes for the real client in `CommitMessageService` tests.

## Threading Rules (IntelliJ-Specific)

These are non-negotiable in plugin code and reviewers will check:
- Anything that takes more than a few ms — network calls, large file reads, LLM requests — **must not run on the EDT**. Use `Task.Backgroundable` with `ProgressManager`. Inside the task, call `ProgressManager.checkCanceled()` between steps so ESC works.
- Reads of PSI/VFS need a read action (`ReadAction.compute { ... }` or `runReadAction { ... }`); writes need a write action wrapped in `WriteCommandAction.runWriteCommandAction(project) { ... }`.
- Touching the UI from a background task must hop back to the EDT via `ApplicationManager.getApplication().invokeLater { ... }`.
- Never show modal dialogs (`Messages.showErrorDialog`) for async results — surface results/errors via `NotificationGroupManager.getInstance().getNotificationGroup("Git Muse")`.

## Secrets

API keys and tokens go through `com.intellij.credentialStore.PasswordSafe`, never `PersistentStateComponent` (which serializes to plaintext XML in the IDE config dir). The `Configurable` settings UI reads/writes via `PasswordSafe` directly — no in-memory caching of the secret.

The `OpenAiCompatibleClient` must never include the `Authorization` header value in any thrown exception, log line, or `LlmError` instance. Tests assert this explicitly.

## Architecture

Source under `src/main/kotlin/com/github/jelenadjuric01/gitmuse/`:

```
action/           AnAction registered into Vcs.MessageActionGroup (Commit dialog toolbar)
llm/              LlmClient interface + OpenAiCompatibleClient impl, Prompt, GenerationResult, sealed LlmError
service/          CommitMessageService (@Service(PROJECT)) — orchestrates prompt → LLM call → return
                  DiffContextBuilder — ChangeListManager → unified diff, truncate, redact secrets
settings/         GitMuseSettings (@State PSC: model, baseUrl, maxDiffChars, requestTimeoutSeconds)
                  GitMuseSecrets — sole PasswordSafe wrapper for the API key
                  GitMuseSettingsConfigurable + GitMuseSettingsComponent
notification/     Notifier — wraps NotificationGroupManager
GitMuseBundle.kt  i18n bundle accessor (currently empty; keys added as the UI grows)
```

Key seams:
- `LlmClient` is the testable boundary. `CommitMessageService` takes one in its constructor; tests substitute `FakeLlmClient` and don't need `BasePlatformTestCase`.
- `DiffContextBuilder` is the only thing that touches `ChangeListManager` — keeps the rest of the code free of IntelliJ VCS APIs and trivially mockable (or replaceable with a pure-JUnit helper).
- The action writes results into the commit message field via `VcsDataKeys.COMMIT_MESSAGE_CONTROL` — never auto-commits.

The `OpenAiCompatibleClient` speaks the OpenAI `/v1/chat/completions` wire format, which OpenAI, Groq, Ollama, OpenRouter, Together, and others all implement. The plugin is therefore provider-agnostic — `baseUrl`, `model`, and the API key are user-configured. There is no preset default; the README ships three side-by-side quick-start configs (Groq / Ollama / OpenAI).
