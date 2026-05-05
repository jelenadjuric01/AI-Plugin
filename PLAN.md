# Implementation Plan: AI Commit Message Generator

## 1. Requirements

**What we're building**
An IntelliJ plugin that adds an action to the VCS Commit dialog toolbar. When invoked, it:
1. Collects the staged diff from the active changelist (`ChangeListManager`).
2. Builds a prompt that constrains the LLM to produce a Conventional Commits-formatted message.
3. Calls an OpenAI-compatible chat-completions endpoint off the EDT.
4. Inserts the response into the commit message field via `VcsDataKeys.COMMIT_MESSAGE_CONTROL`.
5. Never auto-commits, never blocks the EDT, never logs the API key.

Settings page lets the user configure: API key (PasswordSafe), base URL (default `https://api.openai.com/v1`), model (default `gpt-4o-mini`), max diff chars (default `12000`).

**Success criteria for the JetBrains test task**
- Action appears in commit toolbar; clicking it inserts a sensible conventional commit message.
- Cancellable via the progress indicator (ESC in the background-task popup).
- Error states (missing key, HTTP 4xx/5xx, empty diff, timeout) surface as IDE notifications, not modal dialogs.
- Code is small, layered, idiomatic Kotlin: `LlmClient` interface as the test seam, `DiffContextBuilder` isolates VCS APIs, `CommitMessageService` owns orchestration.
- Pure-JUnit tests cover prompt building, LLM response parsing, diff truncation/redaction, and error mapping. One light `BasePlatformTestCase` for the diff builder if needed.
- Plugin builds, `verifyPlugin` passes, no Configuration Cache warnings.

---

## 2. Risks & Mitigations

| # | Risk | Severity | Mitigation |
|---|------|----------|------------|
| R1 | Long diffs blow past the model's context window or rack up cost | **HIGH** | `DiffContextBuilder` truncates per-file and overall to `maxDiffChars`; appends a `[truncated N files]` marker. Hard cap independent of user setting (`50_000`). |
| R2 | `kotlinx-serialization-json` runtime conflict with the platform's bundled JSON or Kotlin stdlib | **HIGH** | Pin to a version aligned with Kotlin 2.1.20 (`1.7.3`); rely on `kotlin.stdlib.default.dependency=false` in `gradle.properties` (already set); do NOT add the serialization Gradle plugin globally — apply only via `plugins { kotlin("plugin.serialization") version "2.1.20" }`. Verify with `./gradlew verifyPlugin`. |
| R3 | OkHttp version conflict with IntelliJ's bundled OkHttp | **HIGH** | Don't bundle OkHttp. Use the JDK-native `java.net.http.HttpClient` (JDK 21) — zero new transitive deps, no shading needed. (Replaces the OkHttp suggestion in the brief; cleaner trade-off.) Note in the plan as a deliberate deviation from the brief. |
| R4 | LLM call on EDT freezes the IDE | **HIGH** | All network in `Task.Backgroundable`; `ProgressManager.checkCanceled()` between steps; UI write back uses `ApplicationManager.getApplication().invokeLater { ... }` on EDT. |
| R5 | API key leaks in logs / exception messages | **HIGH** | `OpenAiClient` never includes the `Authorization` header in any thrown/logged exception. `LlmError` is a sealed type carrying only category + sanitized message. |
| R6 | Configuration Cache breakage from non-serializable task state | MEDIUM | No custom Gradle tasks added. Only declarative `dependencies { ... }` and `plugins { ... }` changes. Run `./gradlew --configuration-cache build` as smoke test. |
| R7 | Action registered in the wrong group — won't appear in commit toolbar | MEDIUM | Register in `Vcs.MessageActionGroup` with `<add-to-group>` and explicit `anchor="last"`. Verify in `runIde` using both Git and a non-Git VCS-less change scenario (action disabled). |
| R8 | `VcsDataKeys.COMMIT_MESSAGE_CONTROL` is null when invoked outside commit dialog | MEDIUM | `update()` disables the action when the data key is null; `actionPerformed()` early-returns with a no-op if null. |
| R9 | Empty staged diff (only untracked files, or nothing selected) | MEDIUM | `DiffContextBuilder` returns `DiffContext.Empty`; service short-circuits with notification "No staged changes". |
| R10 | API key missing on first use | LOW | Action stays enabled but on click checks settings; if key missing, show a notification with a "Configure..." action that opens the Settings page directly. |
| R11 | `PasswordSafe` blocking call on EDT during settings save | LOW | Settings UI's `apply()` runs on EDT but `PasswordSafe` writes are fast and IDE-sanctioned there. Read happens off-EDT inside the background task. |
| R12 | JUnit 4 vs platform test framework mismatch | LOW | `build.gradle.kts` already pulls `TestFrameworkType.Platform`. New pure-JUnit tests use `org.junit.Test`; `BasePlatformTestCase` subclasses use `fun testXxx()` naming. |

---

## 3. Implementation Phases

Each phase is independently buildable and verifiable. Prefer to commit at each phase boundary.

### Phase 1 — Template cleanup & dep wiring  *(~30 min)*

Delete dead template code and add the libraries we'll need.

1. **Delete files**
   - `src/main/kotlin/com/github/jelenadjuric01/gitmuse/services/MyProjectService.kt`
   - `src/main/kotlin/com/github/jelenadjuric01/gitmuse/startup/MyProjectActivity.kt`
   - `src/main/kotlin/com/github/jelenadjuric01/gitmuse/toolWindow/MyToolWindowFactory.kt`
   - `src/test/kotlin/com/github/jelenadjuric01/gitmuse/MyPluginTest.kt` (will be replaced by feature tests)
2. **Edit `plugin.xml`** at `src/main/resources/META-INF/plugin.xml`
   - Remove `<toolWindow>` and `<postStartupActivity>` extension entries.
   - Replace `<description>` with one accurate paragraph about the AI commit message generator.
   - Add `<depends>com.intellij.modules.vcs</depends>` (for VCS data keys + `ChangeListManager`).
3. **Edit `build.gradle.kts`**
   - Add `kotlin("plugin.serialization") version "2.1.20"` to `plugins { ... }`.
   - Add `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")`.
   - **Do NOT add OkHttp** — using JDK 21's `java.net.http.HttpClient`.
   - **Do NOT add `kotlinx-coroutines-core`** — IntelliJ Platform 2025.2 bundles it; explicit add risks `NoSuchMethodError` from version skew. We only use `Task.Backgroundable`, no coroutines, so this is moot anyway.
4. **Edit `settings.gradle.kts`** — change `rootProject.name = "AI-Plugin"`.
5. **Edit `README.md`** — replace template ToDo with: (a) one-paragraph description of the plugin, (b) a "Quick start" section showing three side-by-side configs (Groq / Ollama / OpenAI) with the matching `baseUrl` and example model name for each, (c) defer marketplace badges.

**Verify:** `./gradlew build` succeeds; `runIde` launches with a plugin that does literally nothing (expected).

### Phase 2 — Settings & secrets foundation  *(~1.5 h)*

Plugin needs configurable model/baseUrl/maxDiffChars (PSC) and a securely-stored API key (PasswordSafe). Sequenced before the LLM client so the client can read settings via DI.

Files:
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/settings/AiPluginSettings.kt` — `@State(name = "AiPluginSettings", storages = [Storage("AiPluginSettings.xml")])` `@Service(Service.Level.PROJECT)`-or-application-level `PersistentStateComponent<State>`. Decision: **application-level** (`Service.Level.APP`) — API key/model are user-machine-wide, not per-project. State `data class` is immutable; `loadState`/`getState` use `XmlSerializerUtil.copyBean`. Fields: `model`, `baseUrl`, `maxDiffChars`, `requestTimeoutSeconds`. **No key field.**
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/settings/AiPluginSecrets.kt` — thin object wrapping `PasswordSafe.instance` with one `CredentialAttributes` keyed by `"com.github.jelenadjuric01.gitmuse.OPENAI_API_KEY"`. Methods `getApiKey(): String?`, `setApiKey(value: String?)`, `clear()`. Never logs the value.
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/settings/AiPluginSettingsComponent.kt` — pure Swing/`FormBuilder` panel with `JBTextField` (model, baseUrl), `JBPasswordField` (API key), `JBIntSpinner` (maxDiffChars). Helper label under the model field: `e.g. llama-3.1-8b-instant (Groq), llama3.1:8b (Ollama), gpt-4o-mini (OpenAI)`. No business logic.
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/settings/AiPluginSettingsConfigurable.kt` — implements `Configurable`. `apply()` writes plain fields to `AiPluginSettings` and the password to `AiPluginSecrets`. `reset()` reads from both. `isModified()` compares both. `getDisplayName()` = "Git Muse".
- Register in `plugin.xml` under `<extensions>`: `<applicationConfigurable parentId="tools" instance="...AiPluginSettingsConfigurable" id="aiplugin.settings" displayName="Git Muse"/>` and `<applicationService serviceImplementation="...AiPluginSettings"/>`.

**Verify:** `runIde` → Settings → Tools → "Git Muse" — values persist across sandbox restarts; password is stored in PasswordSafe and not in the XML state file.

### Phase 3 — LLM client + prompt  *(~2 h)*

Pure logic, no IDE dependencies. Highest test value.

Files (all under `src/main/kotlin/com/github/jelenadjuric01/gitmuse/llm/`):
- `Prompt.kt` — single `object Prompt` with `const val SYSTEM` (the Conventional Commits system prompt) and `fun build(diff: String, branch: String?): List<ChatMessage>`. System prompt explicitly forbids code fences, surrounding prose, and demands `<type>(<scope>): <subject>` followed by an optional body. <50 lines.
- `GenerationResult.kt` — `data class GenerationResult(val message: String, val tokensUsed: Int?)`.
- `LlmError.kt` — sealed interface. Variants: `MissingApiKey`, `InvalidResponse(val detail: String)`, `HttpError(val status: Int, val bodySnippet: String)`, `Network(val cause: Throwable)`, `Timeout`, `Cancelled`. `bodySnippet` is `take(500)`; never includes request headers.
- `LlmClient.kt` — `interface LlmClient { fun generate(messages: List<ChatMessage>): Result<GenerationResult> }` plus `data class ChatMessage(val role: String, val content: String)`. `Result<T>` (Kotlin's built-in) wraps success/failure for testability; failures carry `LlmError` via `Result.failure(LlmException(error))`.
- `OpenAiCompatibleClient.kt` — concrete impl that speaks the OpenAI `/v1/chat/completions` wire format (used by OpenAI, Groq, Ollama, OpenRouter, Together, etc.). Constructor takes `apiKey: String, baseUrl: String, model: String, timeout: Duration` (no service lookups inside — plain class, easy to test). Uses `java.net.http.HttpClient.newBuilder().connectTimeout(...).build()`. Body serialized via `kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }`. Maps HTTP outcomes to `LlmError` variants. Strips `assistant.content` to a single trimmed string. <120 lines.
- `ChatCompletionDtos.kt` — `@Serializable internal` request/response DTOs (`ChatRequest`, `Choice`, `Usage`, `Message`, `ChatResponse`). Internal so they don't leak.

**Verify:** Pure-JUnit tests in Phase 6 cover this directly; no `runIde` step here.

### Phase 4 — Diff builder & service  *(~1.5 h)*

Glue between IntelliJ VCS and the LLM layer.

Files:
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/service/DiffContextBuilder.kt` — single `class DiffContextBuilder(private val project: Project)`. Method `build(maxChars: Int): DiffContext`. Internally:
  1. `ChangeListManager.getInstance(project).defaultChangeList.changes` (active changelist; the Commit dialog operates on selected changes, but for v1 we use the default list — simpler and matches "generate from staged").
  2. For each `Change`, get before/after `ContentRevision`, compute a unified-diff string via IntelliJ's `com.intellij.diff.comparison.ComparisonManager` if convenient, else a hand-rolled per-file header (`--- a/path`, `+++ b/path`) plus the textual content delta. v1 ships with a simple representation: per-file header + "before" hash + "after" excerpt; a clean unified diff is a stretch goal if time permits.
  3. Truncate per-file at `maxChars / numFiles`; truncate overall at `maxChars`. Skip binary files. Redact lines matching common secret patterns (basic regex for `(?i)(api[_-]?key|secret|token|password)\s*[:=]`).
  4. Return `DiffContext(diffText, fileCount, truncated)` or `DiffContext.Empty`.
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/service/CommitMessageService.kt` — `@Service(Service.Level.PROJECT)` `class CommitMessageService(private val project: Project)`. Method `generate(): Result<GenerationResult>`:
  1. Read settings + API key.
  2. Build diff via `DiffContextBuilder`. Empty → `Result.failure(LlmException(LlmError.NoChanges))`.
  3. Construct `OpenAiClient` from settings.
  4. Call `client.generate(Prompt.build(...))`.
  5. Returns `Result`. Does NOT show notifications — that's the action's job.
- Constructor overload (or secondary factory) `CommitMessageService(project, clientFactory: (Settings) -> LlmClient)` so tests inject a `FakeLlmClient`. Or simpler: extract a function `generateWith(client: LlmClient, diff: String): Result<GenerationResult>` that's purely testable.

Register in `plugin.xml`: `<projectService serviceImplementation="...CommitMessageService"/>`.

### Phase 5 — Action wiring + notifications + plugin.xml  *(~1.5 h)*

Files:
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/notification/Notifier.kt` — `object Notifier` with `info(project, msg)`, `warn(project, msg)`, `error(project, msg, action: AnAction? = null)`. Uses `NotificationGroupManager.getInstance().getNotificationGroup("Git Muse")`.
- `src/main/kotlin/com/github/jelenadjuric01/gitmuse/action/GenerateCommitMessageAction.kt` — `class GenerateCommitMessageAction : AnAction()`.
  - `update(e)`: enable iff `e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null`. Set icon to `AllIcons.Actions.Lightning` (or similar built-in — never bundle a custom icon for v1).
  - `actionPerformed(e)`:
    1. `val control = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) as? CommitMessage ?: return`.
    2. If API key missing → `Notifier.error` with a "Configure..." action that opens settings via `ShowSettingsUtil.getInstance().showSettingsDialog(project, AiPluginSettingsConfigurable::class.java)`.
    3. Run `Task.Backgroundable(project, "Generating commit message", true)` whose `run(indicator)` calls `service.generate()`. On success, `invokeLater` on EDT to `control.setCommitMessage(result.message)`. On failure, map `LlmError` → notification message (no key/header content).
  - `getActionUpdateThread() = ActionUpdateThread.BGT` (2025.2 requires explicit choice).
- **`plugin.xml`** additions:
  - `<extensions>`: `<notificationGroup id="Git Muse" displayType="BALLOON" isLogByDefault="false"/>`.
  - `<actions>`:
    ```xml
    <action id="aiplugin.GenerateCommitMessage"
            class="...action.GenerateCommitMessageAction"
            text="Generate AI Commit Message"
            description="Generate a Conventional Commits message from the staged diff using an LLM">
      <add-to-group group-id="Vcs.MessageActionGroup" anchor="last"/>
    </action>
    ```

**Verify:** Manual checklist (section 6).

### Phase 6 — Tests + polish  *(~1.5 h)*

See section 5 for the per-class test plan. Polish:
- Run `./gradlew check` → fix any `verifyPlugin` warnings (deprecated APIs, missing `getActionUpdateThread`).
- Update `README.md` with one-paragraph description, screenshot placeholder, and "How to use: configure API key in Settings → Tools → Git Muse, then click the lightning icon in the commit toolbar".
- Update `CHANGELOG.md` "Unreleased" with `Added — AI Commit Message Generator action`.

---

## 4. Dependencies (`build.gradle.kts`)

Add to `plugins { ... }`:
```
kotlin("plugin.serialization") version "2.1.20"
```

Add to `dependencies { ... }`:
```
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
```

**Not added (deliberate):**
- **OkHttp** — replaced by JDK 21 `java.net.http.HttpClient`. Reason: the IntelliJ Platform bundles OkHttp 4.x; pulling our own creates `NoSuchMethodError` risk on platform updates, and JDK 21 HttpClient is a clean, dependency-free fit for a single endpoint. This is a **deviation from the brief** — flag for user approval (Open Question Q1).
- **kotlinx-coroutines-core** — IntelliJ Platform 2025.2.6.2 bundles it. We're using `Task.Backgroundable` (no coroutines), so we don't need it at all. If the user later wants coroutines, mark it `compileOnly` and rely on the bundled runtime version.
- **kotlin-stdlib** — `kotlin.stdlib.default.dependency=false` is set; the platform provides it.

---

## 5. Test Plan

Coverage target: **80%+ on `llm/`, `service/CommitMessageService.kt`, `service/DiffContextBuilder.kt`** (the non-IDE-glue code). Action and Configurable are exercised manually.

| Test class | Path | Base | Covers |
|---|---|---|---|
| `PromptTest` | `src/test/kotlin/.../llm/PromptTest.kt` | plain JUnit 4 | System prompt is non-empty, contains "Conventional Commits"; `build()` returns 2 messages with correct roles; diff is embedded verbatim. |
| `OpenAiCompatibleClientTest` | `src/test/kotlin/.../llm/OpenAiCompatibleClientTest.kt` | plain JUnit 4 + a stub `HttpServer` from JDK `com.sun.net.httpserver` (zero deps). | 200 happy path → `GenerationResult.message` matches first choice content; 401 → `LlmError.HttpError(401, ...)`; malformed JSON → `LlmError.InvalidResponse`; timeout → `LlmError.Timeout`; ensures Authorization header is set; **asserts API key never appears in any thrown exception's message or stacktrace**. |
| `LlmErrorTest` | `src/test/kotlin/.../llm/LlmErrorTest.kt` | plain JUnit 4 | `bodySnippet` is truncated to 500 chars; sealed exhaustiveness via a `when` smoke test. |
| `DiffContextBuilderTest` | `src/test/kotlin/.../service/DiffContextBuilderTest.kt` | `BasePlatformTestCase` (touches VFS/`ChangeListManager`) | Empty changelist → `DiffContext.Empty`; single text-file change → diff contains path + content; binary file skipped; over-cap diff truncated with marker; secret-pattern line redacted. Use `myFixture.addFileToProject` + `ChangeListManagerImpl.getInstanceImpl(project).addUnversionedFiles(...)` to seed changes. **Note:** if seeding `Change` objects is awkward in the test fixture, fall back to extracting a pure helper `fun renderDiff(changes: List<TestChange>, maxChars: Int): DiffContext` and unit-testing that with plain JUnit. Prefer this fallback — keeps the test fast. |
| `CommitMessageServiceTest` | `src/test/kotlin/.../service/CommitMessageServiceTest.kt` | plain JUnit 4 | Inject `FakeLlmClient` returning canned response → service returns it. Inject failing client → service propagates `LlmError`. Empty diff → `LlmError.NoChanges`. Achieved by extracting `generateWith(client, diffText)` so the test doesn't need a `Project`. |
| `FakeLlmClient` | `src/test/kotlin/.../testutil/FakeLlmClient.kt` | n/a | Test helper; configurable response/error queue. |

Tests left to manual verification (acceptable trade-off given task scope):
- Settings UI round-trip — would require `BasePlatformTestCase` and offers little signal vs. manual check.
- Action `update()`/`actionPerformed()` — covered by the manual checklist; would need heavy fixture setup (Commit dialog data context) for marginal value.

---

## 6. Manual Verification Checklist

Run `./gradlew runIde`. In the sandbox IDE:

1. **Settings persist**
   - Settings → Tools → Git Muse. Configure per the README quick-start (recommended for free testing: model `llama-3.1-8b-instant`, baseUrl `https://api.groq.com/openai/v1`, paste a free Groq key), max diff `12000`. Apply.
   - Reopen settings → values reload, password field is empty (good — never displays the stored secret).
   - Restart sandbox IDE → settings + key still present.
2. **Action appears, disabled when not in commit context**
   - Open any project. Confirm the action does NOT appear in the main menu/toolbar.
   - Open Commit tool window (Cmd-K / Ctrl-K). Confirm the lightning icon appears in the message toolbar.
3. **Happy path**
   - Modify 1-2 files, stage them, open Commit dialog.
   - Click the action. Background task popup appears with "Generating commit message".
   - On completion, the commit message field is populated with a Conventional Commits-formatted message. **Verify nothing was committed** (commit button still requires a click).
4. **Cancel mid-flight**
   - Repeat step 3 but immediately click the X / press ESC on the background task popup.
   - No notification, no message inserted. No exception in `idea.log`.
5. **Error path — bad key**
   - Settings → set API key to garbage. Repeat step 3.
   - Notification appears with text mentioning "401" (or "authentication"). Verify the **key value is NOT in the notification, the log, or any stack trace** in `idea.log`. Notification has a "Configure..." link that opens Settings.
6. **Error path — empty diff**
   - Discard all changes. Open commit dialog (it'll be empty). Click the action.
   - Notification: "No staged changes to summarize." No network call made.
7. **Error path — missing key**
   - Settings → clear API key. Click the action.
   - Notification: "API key not configured." with "Configure..." action.
8. **No EDT freeze**
   - With a slow/unreachable base URL (set to `https://10.255.255.1/v1`, which will hang), click the action. Confirm the IDE remains fully interactive while the task is running. Cancel via popup.
9. **No Configuration Cache warnings**
   - `./gradlew --configuration-cache build` — exit code 0, no warnings.
10. **Plugin verifier**
    - `./gradlew verifyPlugin` — no problems reported.

---

## 7. Complexity Estimate

**Overall: Medium.** ~8 hours of focused work for a polished v1.

| Phase | Hours |
|---|---|
| 1. Cleanup + deps | 0.5 |
| 2. Settings + secrets | 1.5 |
| 3. LLM client + prompt | 2.0 |
| 4. Diff builder + service | 1.5 |
| 5. Action + notifications + plugin.xml | 1.5 |
| 6. Tests + polish | 1.5 |
| **Buffer for IntelliJ API surprises** | 0.5 |

The single biggest unknown is `DiffContextBuilder` — getting a clean unified diff out of `Change` objects in 2025.2 may require the `com.intellij.diff` API rather than `ChangeListManager` alone. The fallback (per-file header + truncated `afterRevision.content`) is acceptable for v1 and removes that risk.

---

## 8. Open Questions

**Q1 (medium-impact, recommend approval):** Replace OkHttp with JDK 21 `java.net.http.HttpClient`. Rationale: zero new deps, no version-conflict risk with the platform-bundled OkHttp, sufficient for one endpoint. Brief specified OkHttp; flagging the deviation.

**Q2 (low-impact, recommend yes):** The brief says "OpenAI-compatible — `baseUrl` configurable". I'm planning to expose `baseUrl` in settings (default `https://api.openai.com/v1`), which lets users point at Ollama (`http://localhost:11434/v1`), Together, Groq, etc. for free. Confirm this is the intent and not "OpenAI only".

**Q3 (resolved):** No default `model` or `baseUrl` — both empty in fresh installs. Reason: the model name is provider-specific (`gpt-4o-mini` for OpenAI, `llama-3.1-8b-instant` for Groq, `llama3.1:8b` for Ollama) and presetting any of them implies a recommendation we shouldn't make. The settings field has helper text listing the three provider/model pairings, and the README ships with three side-by-side quick-start configs — user picks one. Submission is bill-free out of the box: reviewer can use Groq's free tier or run Ollama locally.

**Q4 (clarification):** "Staged diff" — the Commit dialog operates on user-selected changes within the active changelist. v1 plan uses the **default changelist's full set of changes** (matches `git status` "to-be-committed" reasonably well). A more precise approach would read the dialog's currently-checked changes via `CommitMessageI`/`CommitWorkflowHandler` APIs, which is more code. Going with default-changelist for v1 unless you want the precise variant.

**Q5 (nice-to-have):** Should the action also offer a keyboard shortcut? My instinct: no default shortcut (avoid conflicts); users can bind one in Keymap. Confirm.

---

**WAITING FOR CONFIRMATION**: Proceed with this plan? (yes / modify: ... / different approach: ...)
