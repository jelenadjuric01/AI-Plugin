# GitMuse

![Build](https://github.com/jelenadjuric01/GitMuse/workflows/Build/badge.svg)

An IntelliJ plugin that generates [Conventional Commits](https://www.conventionalcommits.org/) messages from your staged diff using an LLM.

JetBrains IDEs already ship a similar feature as part of AI Assistant — so why build this? Because I really like that feature and wanted to dive into it myself: see how I would implement it, what the IntelliJ Platform actually exposes for a plugin like this, and what challenges show up along the way (threading rules, secret storage, diff capture, prompt shaping, provider neutrality). GitMuse is as much a learning exercise as it is a working plugin — the result of trying to build the thing I admired and understanding it end-to-end.

GitMuse adds a button to the IDE's Commit dialog. Click it, and a clear, format-correct commit message appears in the message field — for you to review, edit, and commit yourself. The plugin never commits for you, never fires unprompted, and never blocks the IDE while it waits for the model.

It's provider-agnostic: any endpoint that speaks the OpenAI `/v1/chat/completions` wire format works. That covers OpenAI, Groq's free tier, a local Ollama server, OpenRouter, Together, and most others. You bring the `baseUrl`, `model`, and (where required) the API key.

## Installation

During local development:

```bash
./gradlew runIde
```

This launches a sandbox IDE with the plugin pre-loaded. Settings persist across restarts — your sandbox config lives at `.intellijPlatform/sandbox/GitMuse/<idea-version>/config/`.

To produce a distributable `.zip`:

```bash
./gradlew buildPlugin
# output: build/distributions/GitMuse-<version>.zip
```

Install via `Settings → Plugins → ⚙ → Install plugin from disk…` and point at the produced zip.

## Configuration

Open `Settings → Tools → Git Muse` and fill in three fields:

| Provider | `Base URL` | Example `Model` | API key |
| --- | --- | --- | --- |
| **Groq** (free tier) | `https://api.groq.com/openai/v1` | `llama-3.1-8b-instant` | [console.groq.com/keys](https://console.groq.com/keys) |
| **Ollama** (local, no key) | `http://localhost:11434/v1` | `llama3.1:8b` | leave blank |
| **OpenAI** | `https://api.openai.com/v1` | `gpt-4o-mini` | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) |

Two more knobs are available with sensible defaults:

- `Max diff characters` — caps the diff size sent to the model. Default `12000` (~3000 tokens). Hard upper bound `50000` regardless of what you type.
- `Request timeout (seconds)` — entire round-trip timeout. Default `30`.

The API key is stored via IntelliJ's `PasswordSafe` (macOS Keychain / Windows Credential Manager / libsecret), never in plaintext config. The settings field opens empty on every visit — type a new value to set or replace, leave it blank to keep the existing one.

## Usage

1. Make some changes.
2. Open the Commit dialog (`Cmd-K` / `Ctrl-K`).
3. Click the **Generate AI Commit Message** lightning icon in the message toolbar.
4. A background-task popup shows `Generating commit message`. The IDE stays interactive; press ESC on the popup to cancel.
5. After 1–3 seconds, the commit message field fills in with something like:

   ```
   feat(auth): add OAuth2 refresh-token handling

   Refreshes the access token automatically when it expires
   instead of forcing the user to log in again.
   ```

6. Edit if needed, then **Commit** yourself. The plugin's job ends at step 5.

Errors (missing key, auth failure, network, no staged changes, unconfigured base URL) surface as IDE notifications, not modal dialogs. Failures the user can fix in settings include a `Configure…` link that jumps straight to the page.

## How it works

When you click the button, the plugin:

1. Reads the diff of your active changelist via IntelliJ's VCS APIs (skipping binaries, redacting obvious secrets, capping total size).
2. Wraps it in a chat-completion prompt that constrains the model to Conventional Commits format.
3. Sends it to your configured `baseUrl` over HTTPS, off the EDT, in a cancellable background task.
4. Writes the response into the commit message field via `VcsDataKeys.COMMIT_MESSAGE_CONTROL`.

The commit message field is the only place the plugin writes — it never invokes commit, push, or any other VCS action.

## Project layout

```
src/main/kotlin/com/github/jelenadjuric01/gitmuse/
├── action/
│   └── GenerateCommitMessageAction.kt    AnAction registered in Vcs.MessageActionGroup
├── llm/
│   ├── ChatMessage.kt                    @Serializable role/content pair
│   ├── ChatCompletionDtos.kt             internal request/response DTOs
│   ├── GenerationResult.kt               success payload
│   ├── LlmClient.kt                      single-method interface (test seam)
│   ├── LlmError.kt                       sealed error variants + LlmException wrapper
│   ├── OpenAiCompatibleClient.kt         JDK HttpClient impl
│   └── Prompt.kt                         system prompt + builder
├── service/
│   ├── CommitMessageService.kt           @Service(PROJECT) orchestrator
│   ├── DiffContext.kt                    sealed Empty/Present
│   └── DiffContextBuilder.kt             ChangeListManager → unified diff, redact, truncate
├── settings/
│   ├── GitMuseSettings.kt                @Service(APP) PSC for baseUrl/model/limits
│   ├── GitMuseSecrets.kt                 sole PasswordSafe wrapper
│   ├── GitMuseSettingsComponent.kt       Swing form
│   └── GitMuseSettingsConfigurable.kt    Configurable, wires component to state
├── notification/
│   └── Notifier.kt                       NotificationGroupManager wrapper
└── GitMuseBundle.kt                      i18n bundle accessor
```

40+ unit tests cover prompt building, the OpenAI client (happy path, 401/5xx, malformed JSON, body-snippet truncation, header normalization, no-secret-leakage), the orchestration seam, and secret-pattern redaction.

## Building from source

JDK 21 required. The Gradle wrapper is checked in.

```bash
./gradlew runIde            # launch a sandbox IDE with the plugin loaded
./gradlew test              # unit tests
./gradlew check             # tests + verifications
./gradlew verifyPlugin      # JetBrains Plugin Verifier (compatibility check)
./gradlew buildPlugin       # produce distributable .zip
./gradlew patchChangelog    # sync CHANGELOG.md "Unreleased" section
```

Run a single test class:

```bash
./gradlew test --tests "com.github.jelenadjuric01.gitmuse.llm.PromptTest"
```

CI (`.github/workflows/build.yml`) runs `buildPlugin`, `check`, and `verifyPlugin` on every push to main and on pull requests.

---

## Design decisions

These are the calls that shaped the plugin and aren't obvious from the code alone.

### Plugin scope

- **The plugin never commits and never fires unprompted.** It only writes text into the commit message field; you always click Commit yourself. No background poller, no on-error trigger, no auto-suggest — a predictable side-effect surface.

### Provider strategy

- **Provider-agnostic via the OpenAI wire format.** OpenAI's `/v1/chat/completions` shape (`messages: [{role, content}]`, `choices[].message.content`, `Authorization: Bearer …`) became the de-facto industry standard. Groq, Ollama, OpenRouter, Together, Mistral, Cerebras, and others all implement it. One client class works against all of them — switch by changing `baseUrl`.
- **The user picks the model.** Provider catalogs differ: `gpt-4o-mini` is OpenAI, `llama-3.1-8b-instant` is Groq, `llama3.1:8b` is Ollama. Hard-coding any one would constrain users to that provider; pre-setting OpenAI's would also imply a recommendation that bills them. The settings field has helper text listing the three pairings, and this README ships three quick-start configs side by side.
- **The user supplies the API key.** Plugin authors who ship a key risk leaking it or paying for everyone's traffic. User-supplied keys also let people use providers I haven't heard of without any code changes.
- **One provider implementation, not a multi-provider factory.** All compatible endpoints share the same wire format; the abstraction lives at the `LlmClient` interface, not in a registry of provider classes. Adding `AnthropicClient`, `GeminiClient`, etc. preemptively would be ceremony — if a non-OpenAI-shaped provider matters later, a second `LlmClient` plus a small per-provider mapper handles it cleanly.
- **`baseUrl` is configurable, not fixed to OpenAI.** Defaulting to OpenAI would be the only path that costs the user money out of the box; leaving it empty makes the choice deliberate.

### Settings & secrets

- **API key in `PasswordSafe`, never in `PersistentStateComponent`.** PSC serializes to plaintext XML in the IDE config dir. PasswordSafe writes to the OS keychain (macOS Keychain, Windows Credential Manager, libsecret/KWallet on Linux). All key access is funnelled through `GitMuseSecrets` — exactly one class touches the secret store, which makes audit and review trivial.
- **Application-level service, not project-level.** The API key, base URL, and model are per-user, not per-project. A per-project setup would force re-entering the key in every checkout, and there's nothing project-specific about which LLM you use.
- **The password field always opens empty.** Pre-filling exposes the stored key visually; pre-filling with dots leaks its length. The chosen UX: type a value to set or replace, leave blank to keep the existing one. A separate "Clear" button was considered and dropped as marginal UX value for more code surface — to clear, just overwrite with a new value, or delete the entry via the system keychain UI.
- **Default `maxDiffChars` = 12,000; hard cap = 50,000.** 12k characters is roughly 3k tokens — enough context for typical commits (a handful of files, a few hundred lines), short enough to stay cheap on every provider's pricing tier, and well under any model's context window. The 50k hard cap protects against pathological cases (someone accidentally stages a 200k-line CSV) regardless of what the user types in the spinner.
- **Default `requestTimeoutSeconds` = 30.** Generous enough for slow free-tier providers under load (Groq occasionally takes 10+ seconds during peaks); short enough that a stuck request gets surfaced rather than hanging forever. Covers the entire round-trip, not just the connect step.
- **The typed password `char[]` is wiped after `apply()`.** Standard hygiene: the value would otherwise sit in heap memory until the next GC, where a heap dump or memory scanner could read it.

### LLM client

- **`LlmError` is a sealed interface — a closed set of failure types** (`MissingApiKey`, `NoChanges`, `Timeout`, `Network`, `HttpError`, `InvalidResponse`). Each one maps to its own user-facing notification: auth failures get a `Configure…` link, network errors say "check your connection", timeouts suggest retry. A single string error message would lose this routing, and the compiler enforces that every variant is handled — no silent gaps.
- **No `Cancelled` variant.** When the user presses ESC on the progress popup, IntelliJ throws `ProcessCanceledException`, which the platform requires plugins let propagate (never catch). The LLM client itself is decoupled from IntelliJ — it just makes a blocking HTTP call. Cancellation cuts in around it, not through it.
- **`apiKey` is nullable; the `Authorization` header is set only when the key is non-blank.** Ollama runs locally without authentication. Sending an empty `Bearer` header would either confuse it or cause confusing failures elsewhere. Omitting the header entirely lets the same code path serve authenticating and non-authenticating servers.
- **`ChatMessage` is both the public API type and the JSON DTO.** OpenAI's message shape (`role`, `content`) is the standard across all compatible providers; wrapping it in a separate "data transfer object" type would be ceremony. If a non-OpenAI-shaped provider matters later, a per-provider mapper is added then — not preemptively.
- **The remaining wire DTOs (`ChatRequest`, `ChatResponse`, `Choice`, `Usage`) are `internal`.** They're an implementation detail of the OpenAI client and shouldn't leak into the rest of the plugin.
- **`temperature = 0.3`.** Temperature controls how much randomness the model adds to its output. 0.0 is fully deterministic but tends to produce stiff, repetitive phrasing across runs; 1.0+ gets creative and unreliable. 0.3 is the sweet spot for structured, format-bound output: similar diffs produce similar messages, but the wording doesn't read like a template.
- **Connect timeout 10 s, request timeout from settings.** Connect-timeout governs the TCP/TLS handshake only; the request timeout covers the full round-trip. Splitting them lets a slow provider fail fast on connect issues while still being patient with a slow generation.
- **`baseUrl` validation up-front.** Empty, malformed, or non-`http(s)` URLs are caught before any HTTP call is attempted, with a distinct error message for each. The most common case this catches: forgetting the `https://` prefix.

### Diff handling

- **Diff captured from the active changelist via `ChangeListManager`.** Maps closely to `git status`'s "to-be-committed" set. Uses the default changelist's full set of changes — precise per-checkbox capture (via `CommitWorkflowHandler`) is more code, and the default-changelist version is correct for the common case.
- **Binary files skipped.** Sending non-text bytes to a chat-completion endpoint is meaningless and burns tokens.
- **Real `git diff`-style hunks via IntelliJ's `IdeaTextPatchBuilder` + `UnifiedDiffWriter`.** Output looks like the diff your terminal shows: `--- a/path` / `+++ b/path` headers, `@@ -X,Y +A,B @@` hunks containing only the changed lines plus a few lines of context. Major signal-per-token win — for a 200-line file with a 5-line change, the prompt sees about 10 lines of relevant diff instead of 400 lines of dumped before+after content. Requires `bundledModule("intellij.platform.vcs.impl")` in `build.gradle.kts` to expose those classes on the compile classpath; they otherwise ship with every IDE so the dependency adds zero runtime weight.
- **Secret-pattern redaction.** Lines matching common secret regexes (`api_key=`, `password:`, `token:`, `Bearer …`, `client_secret=`, …) are replaced with `***REDACTED***` before the diff goes to the LLM. A best-effort safety net, not a guarantee — users with truly sensitive diffs should review before clicking the action.
- **Read-action wrapping.** `DiffContextBuilder.build()` runs inside `runReadAction { … }`. IntelliJ uses a global read/write lock to keep the project model consistent — reading file revisions or changelists from a background thread without acquiring it can throw `ReadAccessNotAllowed` partway through. (`runReadAction` is the Kotlin extension; the older `ReadAction.compute(ThrowableComputable)` is deprecated.)

### Prompt design

- **System prompt enforces Conventional Commits and explicitly forbids code fences and prose.** Without those constraints, models routinely return triple-backticked output or "Sure, here's your commit message:". Spelling out the negatives is the cheapest reliability fix.
- **Explicit unified-diff reading guidance.** The system prompt walks the model through what `--- a/path`, `+++ b/path`, `/dev/null`, `@@`, `+`, and `-` mean, and emphasizes that the diff is the only source of truth. Models trained on lots of code already know this, but stating it explicitly reduces hallucinated changes.
- **Subject line: imperative mood, lowercase, no trailing period, ≤72 chars.** Matches the Conventional Commits ecosystem's de-facto style.
- **Body explains WHY, not WHAT.** The diff IS the "what". Asking the model to restate it in prose wastes tokens and produces weaker reviews.
- **Truncation marker is in-prompt.** When the diff is over the cap, a `[truncated …]` marker is appended and the system prompt tells the model to focus on the visible portion and not speculate. Honest beats clever.

### Threading & UX (IntelliJ-specific)

- **All network in `Task.Backgroundable`, never on the EDT.** The EDT (Event Dispatch Thread) is the single thread that paints every pixel of the IDE; blocking it freezes everything. Anything slower than a few milliseconds — definitely an LLM round-trip — has to run elsewhere.
- **The result writes back to the EDT via `invokeLater`.** Touching Swing components (the commit message field) from a background thread is undefined behavior, so the result hops back to the EDT for the actual write.
- **Notifications, not modal dialogs, for async results.** Modals interrupt whatever the user is doing and queue events on the EDT — exactly the thing we went off-thread to avoid.
- **Action `update()` disables itself when `VcsDataKeys.COMMIT_MESSAGE_CONTROL` is null.** Stops the action from showing up in irrelevant menus and from no-op'ing if invoked where there's no commit message field to write into.
- **Defensive try/catch around `service.generate()` in the action.** `ProcessCanceledException` is rethrown unchanged (platform contract); anything else becomes a clean notification instead of a stack trace in the IDE log.
- **No default keyboard shortcut.** Default shortcuts collide with user customizations and project-specific keymaps. Users can bind one themselves in `Settings → Keymap`.

### Build & dependencies

- **`kotlin.stdlib.default.dependency = false`** in `gradle.properties`. IntelliJ ships its own Kotlin stdlib; bundling another causes runtime `ClassLoader` conflicts that only show up at plugin load time.
- **Configuration Cache enabled** (`org.gradle.configuration-cache = true`). Gradle reuses the configured task graph between invocations, which makes incremental dev cycles noticeably faster. Costs nothing if every task in the build is cache-compatible (they are here).

## Possible improvements

A list of features worth picking up if the plugin grows beyond v1. None are load-bearing for the current behavior.

- **Read the actually-checked changes in the Commit dialog.** v1 sends the full default-changelist diff. The Commit dialog also tracks per-file checkboxes that let the user partially stage; capturing those (via `CommitWorkflowHandler`) would generate a message that matches what gets committed, not what's in the working tree.
- **Branch name as scope hint.** Branch names like `feat/auth-refresh` or `fix/login-redirect-loop` are free context the model could fold into the `<scope>` segment of the header. Plumbing this in cleanly requires a `dynamic` dependency on Git4Idea (so the plugin still loads in IDEs without Git support) and a small reflective lookup. Marginal accuracy win, cheap once wired.
- **Tone / style options.** A "Tone" setting (`Neutral` / `Concise` / `Detailed` / `Casual`) that injects an extra clause into the system prompt. Three lines in the settings UI, one paragraph of prompt change, no architectural impact.
- **Match the project's existing commit style.** Read the last N commits from the project's history and prepend them to the user message as "examples of this project's style". Lets the model match house conventions for scope vocabulary, message length, and type usage. Costs more tokens — should probably be opt-in.
- **Streaming responses.** Show the message as it's generated rather than waiting for the full response. Better perceived latency. Means switching from `HttpClient.send` to `HttpClient.sendAsync` with a server-sent-events body handler and adjusting the EDT-hop pattern.
- **Cache by diff hash.** If the user clicks the button twice without changing anything, return the previous result. Saves tokens; needs a small in-memory `Map<String, GenerationResult>` keyed by SHA-256 of the diff.
- **Real `BasePlatformTestCase` integration test for `DiffContextBuilder`.** Current tests cover pure logic (the redaction regex). A platform-fixture test that seeds an in-memory project with changes and asserts the rendered diff shape would catch IntelliJ-API regressions before users hit them.
- **Internationalization.** All user-facing strings are currently hard-coded English. `GitMuseBundle` is wired up but empty. Moving the notification messages, settings labels, and action text into the bundle would let the plugin localize cleanly.

---

Plugin scaffolded from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
