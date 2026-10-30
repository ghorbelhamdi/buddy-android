# Contributing to Buddy

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/ghorbelhamdi/buddy-android/issues/new/choose) with the bug form. The most useful details are your phone and Android version, the agent and model (Claude Code, Codex, OpenCode, Antigravity or Grok), what you asked, and what happened instead. A screenshot of the chat (open its "N tool calls" line) usually shows where it went wrong.

Found a way for an agent to act without your approval, or to approve for you? Please don't post it publicly: see [SECURITY.md](SECURITY.md).

## Before you write code

- **Small fixes** (a crash, a typo, a wrong label): just open a pull request.
- **Bigger changes** (a new tool, a new screen, a new agent): open an issue first, so we agree on the approach before you spend time on it.
- **Anything that touches safety** (`Guard.java`, `Reviewer.java`, the approval cards, the rules in `linux/system-prompt.md`): always start with an issue. These decide what an agent can do without asking, so they get extra care.

## Building

See [For developers](README.md#for-developers) in the README: `./gradlew assembleRelease`, or `./build.sh` right on the phone in Termux.

Changing how `linux/buddy-run` reads an agent's output? Run `bash linux/test/run.sh`: it feeds recorded runs of each agent (`linux/test/fixtures`) through the helper and compares the events. CI runs it too.

There are two flavors: `github` (the full app) and `play` (without self-updates and developer mode, as Google Play requires). Build the `github` one unless your change is about Play.

## Testing

Buddy drives real apps, so please test on a real phone before opening a pull request:

1. Install your build over the current one (same signing key) or on a clean phone.
2. Run at least one task with an agent, for example "Open the calculator and compute 12 × 7".
3. If you changed approvals or tools, also try something that must ask ("Send me a WhatsApp saying test") and check the card appears, and that Deny really stops it.

Say in the pull request what you tested and on which phone.

## Code style

- Plain Java, no new libraries unless there's no other way. The APK is small on purpose.
- Match the surrounding code: naming, comment density, the `Ui` helpers for views.
- Text the user sees is short and plain: one idea per sentence, no jargon.
- Text the user sees goes in `app/src/main/res/values/strings.xml`, never as a literal in the Java code (whole sentences with `%1$s` placeholders, not pieces glued together). Text only the agent reads (tool descriptions and results, prompts) stays in English in the code.
- `linux/buddy-run` is bash with jq programs inside single quotes: no apostrophes in comments there, or the quoting breaks.
- One topic per pull request. Don't bump the version; releases do that.

## Translations

Buddy's English text is in `app/src/main/res/values/strings.xml`, and each language has its own `values-<code>/strings.xml`. The first translations were made with AI, so corrections from native speakers are very welcome.

- **Fix a translation:** edit that language's file and open a pull request.
- **Add a language:** copy `values/strings.xml` to `values-<code>/strings.xml`, translate it (leave out entries marked `translatable="false"`) and add the code to `res/xml/locales_config.xml`, so it shows up in Android's per-app language setting.
- Keep placeholders like `%1$s` and `%2$d` exactly, and escape apostrophes as `\'`.
- New English text goes in `values/strings.xml`; until it's translated, other languages show it in English.

## License

Buddy is [Apache-2.0](LICENSE). By opening a pull request you agree that your contribution is released under the same license.

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/app/buddy/assistant/BuddyService.java` | Accessibility service: hosts the bubble, the MCP server and the in-control indicator |
| `.../Tools.java` | The phone tools |
| `.../McpServer.java` | Minimal MCP server (streamable HTTP, JSON responses) |
| `.../Bubble.java` | The floating bubble and its chat panel |
| `.../AutoPause.java`, `Marks.java` | Pausing when you take the phone back (a touch, a call), and the marks `point_out` draws on the screen |
| `.../MainActivity.java`, `ChatActivity.java`, `MonitorActivity.java`, `SetupActivity.java` | Chats, a chat, Sessions, Settings |
| `.../OnboardingActivity.java` | First-run setup guide |
| `.../ChatHub.java`, `Sessions.java` | Chat state shared by the app and the bubble |
| `.../Rules.java`, `RulesActivity.java`, `NotificationWatcher.java`, `Triggers.java`, `Waiting.java`, `WaitingActivity.java` | Notification rules: the rules and their screen, notification access, the runs they start, and the replies waiting for your OK |
| `.../Ui.java`, `Theme.java` | Components and the light/dark/accent theme |
| `.../Linux.java` | Buddy's Linux: downloads Alpine and runs commands through proot |
| `.../Helper.java` | Runs `buddy-run` inside Linux and keeps its files up to date |
| `.../Account.java`, `SignInActivity.java` | Install, one-tap sign-in and status for Claude Code, Codex, OpenCode, Antigravity and Grok |
| `.../RemoteSession.java` | The Remote Control session |
| `.../CodexRemote.java` | The Codex session: runs `linux/codex-remote` and passes on pairing requests |
| `.../SshServer.java` | SSH from Termux: the server in Buddy's Linux and the one-time code that lets a key in |
| `app/src/main/res/values/strings.xml` | Every text Buddy shows, in English; `values-<language>/` holds the translations |
| `app/src/main/jniLibs/arm64-v8a/` | proot and its libraries (see [third_party](third_party/README.md)) |
| `linux/buddy-run` | Runs the agent with the phone tools and streams events back |
| `linux/codex-remote` | Codex's app-server with remote control and the phone tools; makes pairing codes in the same proot |
| `linux/system-prompt.md` | Buddy's instructions for the agent |
| `linux/android-app/` | The developer-mode skill: toolchain setup and `build-apk.sh` |
| `app/src/main/assets/linux` | Symlink to `linux/`, so the helper ships inside the APK |
