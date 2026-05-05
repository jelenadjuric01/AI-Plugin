# GitMuse

![Build](https://github.com/jelenadjuric01/GitMuse/workflows/Build/badge.svg)

An IntelliJ plugin that generates [Conventional Commits](https://www.conventionalcommits.org/) messages from your staged diff using an LLM.

GitMuse adds a button to the IDE's Commit dialog. Click it and a clear, format-correct commit message appears in the message field for you to review and edit before committing. The plugin never commits for you, never fires unprompted, and never blocks the IDE while it waits for the model.

This repository is a JetBrains test task — a small but well-structured plugin that exercises the full LLM loop (context capture, prompt engineering, structured output, error mapping, secure secret handling, off-EDT execution) without ballooning into a multi-provider chat platform.

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

## Implementation status

The implementation is phased — each phase ends at a green build and a clean commit. Detailed plan in [`PLAN.md`](PLAN.md).

| Phase | Scope | Status |
| --- | --- | --- |
| 1 | Template cleanup, dependency wiring, project rename to GitMuse | done |
| 2 | Settings page, persisted state, PasswordSafe-backed API key | done |
| 3 | LLM client, prompt builder, error model | done |
| 4 | Diff context builder, commit-message orchestration service | planned |
| 5 | VCS toolbar action, notification group, plugin.xml wiring | planned |
| 6 | Tests + final polish (verifyPlugin, README screenshots) | planned |

---

## Design decisions

Every meaningful trade-off is recorded here so the choices are auditable. Where a decision rules out something obvious, the rationale spells out why.

### Plugin scope

- **A commit-message generator, not a motivational pop-up or a regex helper.** The task is graded on AI-tool fluency and code structure. Commit messages exercise prompt engineering (format constraints), structured output, real diff capture, and error categorization — all of which are weaker in alternatives I considered (motivational error messages, code explanation, regex builder).
- **The plugin never commits and never fires unprompted.** It only writes text into the commit message field; the user always clicks Commit themselves. There is no background poller, no on-error trigger, no auto-suggest. A reviewer can confidently demo this without worrying about side effects.

### Provider strategy

- **Provider-agnostic via the OpenAI wire format.** OpenAI's `/v1/chat/completions` shape (`messages: [{role, content}]`, `choices[].message.content`, `Authorization: Bearer …`) became the de-facto industry standard. Groq, Ollama, OpenRouter, Together, Mistral, Cerebras, Anyscale, and many others all implement the same shape. One client class works against all of them — switch by changing `baseUrl`.
- **Why the user chooses the model.** Different providers expose different model names: `gpt-4o-mini` (OpenAI), `llama-3.1-8b-instant` (Groq), `llama3.1:8b` (Ollama). Hard-coding any one of them constrains the user; pre-setting OpenAI's also implies a recommendation that bills the user. The settings field has helper text listing the three pairings, and the README ships three quick-start configs side by side.
- **Why the user supplies the API key.** Plugin authors who ship a key risk leaking it (or paying for everyone's traffic). User-supplied keys also let people use providers I haven't heard of without code changes.
- **No multi-provider factory with two real implementations.** All compatible endpoints share the same wire format; the abstraction lives at the `LlmClient` interface, not in a sprawling registry of provider classes. If a non-OpenAI-shaped provider matters later, a second `LlmClient` impl plus mapping is small and well-bounded — but adding one preemptively is ceremony for a test task.
- **`baseUrl` is configurable, not fixed to OpenAI.** The whole point of the abstraction. Defaulting to OpenAI would be the only path that costs the user money out of the box; leaving it empty makes the choice deliberate.

### Settings & secrets

- **API key in `PasswordSafe`, never in `PersistentStateComponent`.** PSC serializes to plaintext XML in the IDE config dir. PasswordSafe writes to the OS keychain (macOS Keychain, Windows Credential Manager, libsecret/KWallet on Linux). All key access is funnelled through `GitMuseSecrets` — exactly one class touches the secret store, which makes audit and review trivial.
- **Application-level service, not project-level.** The API key, base URL, and model are per-user, not per-project. A per-project setup would force re-entering the key in every checkout. There is nothing project-specific about which LLM you use.
- **Password field always opens empty.** Pre-filling exposes the stored key visually; pre-filling with dots leaks its length. The chosen UX: type a value to set/replace, leave blank to keep the existing one. To clear, overwrite with a new key (or remove the entry via the system keychain UI). Adding a "Clear" button was considered and rejected as marginal UX value at the cost of more code surface.
- **Default `maxDiffChars` = 12,000; hard cap = 50,000.** 12k characters is roughly 3k tokens — enough context for typical commits (a handful of files, a few hundred lines changed), short enough to stay cheap on every provider's pricing tier, and well under any model's context limit. The 50k hard cap protects against pathological cases (someone accidentally stages a 200k-line CSV) regardless of what the user types in the spinner. The user can lower the limit but cannot raise it past 50k.
- **Default `requestTimeoutSeconds` = 30.** Generous enough for slow free-tier providers under load (Groq's free tier occasionally takes 10+ seconds during peaks); short enough that "stuck" requests get surfaced rather than hanging forever. Covers the entire request, not just the connect step.
- **Wipes the typed `char[]` after `apply()`.** Standard hygiene: the password value would otherwise linger on the heap, where a heap dump or memory scanner could find it. Cheap to do, costs nothing to skip, but the test task is being read by JetBrains — they'll notice the missing line.

### LLM client

- **JDK 21 `java.net.http.HttpClient`, not OkHttp.** IntelliJ bundles its own OkHttp; pulling our own creates `NoSuchMethodError` risk on platform updates and complicates the dependency graph. The JDK client is sufficient for one endpoint, dependency-free, and ships with the JVM. This is a deliberate deviation from the original plan (which suggested OkHttp) — flagged in `PLAN.md` Q1.
- **No explicit `kotlinx-coroutines-core` dependency.** IntelliJ Platform 2025.2 bundles coroutines; declaring our own pulls a version skew. The plugin uses `Task.Backgroundable` (no coroutines) anyway, so the question is moot.
- **`Result<T>` return type, not throwing exceptions.** Forces callers to handle failures explicitly at the call site. The `LlmException` wrapper exists only because Kotlin's `Result.failure` requires a `Throwable` — internally we still pattern-match on the typed `LlmError`.
- **`LlmError` as a sealed interface with categorized variants.** Each variant maps to a different user notification: `MissingApiKey` → "Configure API key" with a link to settings; `Network` → "Check your connection"; `Timeout` → "Try again"; `HttpError` → status-code-aware message. A single string error message would lose this routing.
- **No `Cancelled` variant.** Cancellation is handled at the action layer via IntelliJ's `ProcessCanceledException`, which the platform requires we let propagate (never catch). The LLM client itself is decoupled from IntelliJ; it just makes a blocking HTTP call. Cancellation cuts in around it, not through it.
- **`apiKey: String?` is nullable; the `Authorization` header is set only when the key is non-empty.** Ollama runs locally without authentication — its endpoints reject requests with empty bearer tokens or simply ignore them. By omitting the header entirely when there's no key, the same code path works against authenticating and non-authenticating servers cleanly.
- **`ChatMessage` doubles as the public API type and the wire DTO.** OpenAI's message shape (`role`, `content`) is the standard across all compatible providers; wrapping it in a separate "DTO" type would be ceremony. If a non-OpenAI-shaped provider matters later, a per-provider mapper is added at that point — not preemptively. The `@Serializable` annotation enables the codec without constraining consumers.
- **Wire DTOs (`ChatRequest`, `ChatResponse`, `Choice`, `Usage`) are `internal`.** They're an implementation detail of the OpenAI client and shouldn't leak into the rest of the plugin.
- **`temperature = 0.3`.** Commit messages benefit from determinism — same diff producing the same message — without going to zero (which can produce stiff, repetitive output across runs). 0.3 is the conventional sweet spot for "structured, format-bound generation".
- **`Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }`.** Forward-compatible with provider extensions (some return non-standard fields), keeps the request body minimal (no `null`/default fields cluttering it).
- **Connect timeout 10 s, request timeout from settings.** Connect-timeout governs the TCP/TLS handshake only; the request timeout (`HttpRequest.timeout`) covers the full round-trip. Splitting them lets a slow provider fail fast on connect issues while still being patient with a slow generation.
- **Error sanitization: `LlmError` instances never contain headers, the API key, or full stack traces.** `LlmError.Network(description)` carries only `${exceptionClassName}: ${message}`. `LlmError.HttpError(status, bodySnippet)` truncates the body at 500 chars (provider error responses say things like `"Invalid API key"` — they don't echo the request bearer back). The test suite (Phase 6) explicitly asserts that the API key never appears in any thrown exception's message or stack trace.
- **`baseUrl` trailing slash is stripped.** `https://api.groq.com/openai/v1/` and `https://api.groq.com/openai/v1` both work without a stray `//chat/completions` URL.

### Prompt design

- **System prompt enforces Conventional Commits and explicitly forbids code fences and prose.** Without these constraints, models routinely return triple-backticked output or "Sure, here's your commit message:". Spelling out the negatives in the system prompt is the cheapest reliability fix.
- **Subject line: imperative mood, lowercase, no trailing period, ≤72 chars.** Matches the Conventional Commits ecosystem's de-facto style.
- **Body explains WHY, not WHAT.** The diff IS the "what". Asking the model to restate the diff in prose is wasted tokens and produces weaker reviews.
- **Branch name passed alongside the diff.** Branch names often hint at scope (`feat/oauth-refresh` → `(auth)`). Free context the model can fold into the type/scope segment.
- **Truncation marker is in-prompt.** When the diff is over the cap, a `[truncated …]` marker is appended to the body, and the system prompt instructs the model to focus on the visible portion and not speculate. Honest beats clever.

### Threading & UX (IntelliJ-specific)

- **All network in `Task.Backgroundable`, never on the EDT.** Plugin code that blocks the EDT freezes the IDE — the single most common reviewer-fail pattern in IntelliJ plugins.
- **Result writes back to the EDT via `invokeLater`.** Touching Swing components (the commit message field) from a background thread is undefined behavior.
- **Notifications, not modal dialogs, for async results.** Modals interrupt the user and queue events on the EDT, defeating the point of going off-thread. The Notifications API is the IntelliJ-native non-blocking surface.
- **Action `update()` disables itself when `VcsDataKeys.COMMIT_MESSAGE_CONTROL` is null.** Stops the action from showing up in irrelevant menus and from no-op'ing if invoked from a context where there's no commit message field to write into.
- **No default keyboard shortcut.** Default shortcuts collide with user customizations and project-specific keymaps. Users can bind one in `Settings → Keymap`.

### Diff handling (Phase 4 — planned)

- **Diff captured from the active changelist via `ChangeListManager`.** Maps closely to `git status` "to-be-committed" set. v1 uses the default changelist's full set of changes — precise per-checkbox capture (via `CommitWorkflowHandler`) is more code, and the default-changelist version is correct for the common case.
- **Binary files skipped.** Sending non-text bytes to a chat-completion endpoint is meaningless and burns tokens.
- **Secret-pattern redaction.** Lines matching common secret regexes (`api_key=`, `password:`, `token:`, …) are replaced with redaction markers before the diff goes to the LLM. A best-effort safety net, not a guarantee — users with truly sensitive diffs should review before clicking the action.

### Build & dependencies

- **`kotlin.stdlib.default.dependency = false`** in `gradle.properties`. IntelliJ ships its own Kotlin stdlib; bundling another causes runtime `ClassLoader` conflicts. The setting is a foot-gun preventer.
- **Configuration Cache enabled** (`org.gradle.configuration-cache = true`). Faster Gradle invocations during dev. If a task fails with a cache-related error, the right fix is in the task — not disabling the cache project-wide.
- **`kotlinx-serialization-json:1.7.3`, aligned to Kotlin 2.3.21.** Mismatched versions surface as runtime `NoSuchMethodError`s. The serialization compiler plugin is applied via `kotlin("plugin.serialization") version "2.3.21"` so the version always tracks Kotlin's.
- **JUnit 4, not JUnit 5.** The IntelliJ test framework expects JUnit 4. Tests under `BasePlatformTestCase` use the `testXxx()` naming convention — backtick-quoted names aren't picked up by that runner. Pure-logic tests (no IDE fixture) can use plain JUnit 4 freely.
- **`<depends>com.intellij.modules.vcs</depends>`** in `plugin.xml`. Required for `VcsDataKeys` and `ChangeListManager`. Without it, the plugin would still load in IDEs without the VCS module bundled (rare, but possible) and crash at action-invocation time.

## What's deliberately NOT here

These were considered and rejected — listing them so the boundary is explicit.

- **A multi-provider factory with two real implementations.** One interface + one impl is sufficient when the wire format is shared; adding `AnthropicClient`, `GeminiClient`, etc. is preemptive abstraction.
- **Streaming UI / inline completion / chat panel.** Out of scope. The plugin is one button, one popup, one result.
- **RAG over the project, embeddings, vector store.** Significantly more complex than the test task warrants. Commit messages don't need full project context — the diff is enough.
- **Auto-commit / auto-push.** The plugin only writes text into the message field; the user always pulls the trigger.
- **A "Clear stored key" button.** Setting a new key replaces the old; clearing is rare enough that the system keychain UI is fine. Avoids three-button settings clutter.
- **Per-language doc generation, regex tools, motivational error messages.** Considered as alternative plugin ideas; rejected as either weaker AI signal (motivational) or off-spec for the test task (multiple smaller features competing for attention).
- **Custom icon assets.** v1 uses an IntelliJ-bundled `AllIcons` value. Shipping a custom icon is a polish item, not a feature.
- **Settings-side validation of `baseUrl` / `model`.** The provider returns an actionable error message faster than custom client-side validation can. Letting the round-trip surface a 401 / 404 / "Model not found" error is more honest than guessing what the user meant.

## Development

```bash
./gradlew runIde            # launch a sandbox IDE with the plugin loaded
./gradlew test              # unit tests
./gradlew check             # tests + verifications
./gradlew verifyPlugin      # JetBrains Plugin Verifier
./gradlew buildPlugin       # produce distributable .zip in build/distributions/
```

JDK 21 required.

The implementation plan, including phase boundaries and risks, is in [`PLAN.md`](PLAN.md). Repo guidance for future Claude Code sessions is in [`CLAUDE.md`](CLAUDE.md).

---
Plugin scaffolded from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
