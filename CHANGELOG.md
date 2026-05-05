<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# GitMuse Changelog

## [Unreleased]

### Added
- **AI commit message generator.** New `Generate AI Commit Message` action in the
  Commit dialog message toolbar. Reads the staged diff, sends it to an
  OpenAI-compatible chat-completions endpoint with a Conventional Commits
  system prompt, and writes the result into the commit message field.
- Settings page under `Tools → Git Muse` for `Base URL`, `Model`,
  `API key` (PasswordSafe-backed), `Max diff characters` (default 12 000),
  and `Request timeout (seconds)` (default 30).
- Provider-agnostic via the OpenAI wire format — works with OpenAI, Groq's
  free tier, a local Ollama server, OpenRouter, and other compatible
  endpoints. README ships three side-by-side quick-start configurations.
- Diff-time secret redaction — best-effort regex strips `api_key=…`,
  `password: …`, `Authorization: Bearer …`, and similar shapes before the
  diff is sent.
- Notification group `Git Muse` for surfacing async errors, with a
  `Configure…` link that jumps directly to the settings page on auth or
  configuration failures.
- 40 unit tests covering prompt building, the OpenAI client (happy path,
  401/5xx, malformed JSON, empty choices, body-snippet truncation, header
  normalization, and an explicit no-secret-leakage assertion), the
  orchestration seam, and secret-pattern redaction.

### Changed
- Renamed project from `AI-Plugin` to `GitMuse`. Plugin id, package, and
  i18n bundle moved accordingly.

### Removed
- IntelliJ Platform Plugin Template scaffolding (`MyToolWindowFactory`,
  `MyProjectActivity`, `MyProjectService`, sample test, test fixtures).
