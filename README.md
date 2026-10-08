# Buddy

**A floating chat bubble that lets Claude Code (or Codex) use your Android phone.**

Type a request into the bubble ("reply to Sam that I'm running late", "add a Nintendo Switch 2 to my Amazon cart") and Buddy reads the screen, taps, types and scrolls to get it done. It asks for your approval before anything that sends, posts, buys or deletes.

Buddy runs on **your own subscription**: [Claude Code](https://docs.claude.com/en/docs/claude-code) with your Claude plan, or OpenAI's Codex CLI with your ChatGPT plan, both running in [Termux](https://termux.dev) on the phone. The app has no API key, no server and no account of its own.

**[⬇ Download the APK](https://github.com/ghorbelhamdi/buddy-android/releases/latest/download/buddy.apk)** (Android 11+)

> **Status: 1.0, early.** It works well day to day but still has rough edges. Tested on a OnePlus 12 (OxygenOS, Android 16); other Android 11+ phones should work, but may need battery settings adjusted.
>
> Buddy is an independent project. It is not made by or affiliated with Anthropic or OpenAI.

## Not another adb setup

Most "AI controls my phone" projects drive the phone over adb, which needs a computer or Wireless debugging that drops all the time. Buddy doesn't use adb at all:

- It's a normal Android app with an **accessibility service**, the same kind of permission screen readers use.
- **No adb, no Wireless debugging, no PC, no root.**
- Works **without Wi-Fi** and comes back on its own after a reboot.

## How it works

```
Buddy app / bubble ──RUN_COMMAND──► Termux: buddy-run ──► claude -p  or  codex exec
        ▲                                                       │
        └──────────── streamed replies (POST /event) ◄──────────┤
                                                                ▼
Buddy accessibility service ◄─── MCP over http://127.0.0.1:8765/mcp (phone tools)
```

- The **accessibility service** draws the bubble and runs a small local **MCP server** with the phone tools: `read_screen`, `tap`, `long_press`, `type_text`, `scroll`, `swipe`, `press`, `open_app`, `list_apps`, `open_url`, `screenshot`, `wait`, `wait_for_install`, `confirm` and `notify`.
- Your message goes to the agent in Termux, which uses those tools and streams its progress back to the app.
- The same MCP server works from **your own Claude Code or Codex sessions** (see below).

## Setup

You need an Android 11+ phone and a Claude Pro/Max plan (or a ChatGPT plan for Codex).

1. **Install Buddy:** [**download the APK**](https://github.com/ghorbelhamdi/buddy-android/releases/latest/download/buddy.apk) and open it (allow your browser to install apps when asked). All versions are on the [Releases](https://github.com/ghorbelhamdi/buddy-android/releases) page.
2. **Follow the setup guide.** It opens on first launch (later: Settings → Setup → *Open the step-by-step guide*). Each step ticks itself off when you come back, and while you're in Termux Buddy checks in the background and returns by itself.

| Step | What happens |
|---|---|
| Choose your agent | **Claude Code** (recommended), **Codex**, or both |
| Install Termux | From F-Droid or GitHub, not the outdated Play Store version |
| Accessibility | Turn on *Buddy assistant*. Greyed out ("Restricted setting")? App info → ⋮ → **Allow restricted settings**, then try again |
| Allow Termux commands | One Android permission prompt |
| Connect Termux | Buddy copies one command; paste it in Termux. It installs Buddy's helper and enables `allow-external-apps` |
| Install and sign in | Buddy copies the install command for your agent. Claude Code uses the community [claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android) installer, then `claude` to log in. Codex installs from npm with a small wrapper, then `codex login` |
| Keep it running | Battery: unrestricted for Buddy and Termux |
| Try it | A first chat with a ready-made prompt |

### Phone tools in your own sessions (optional)

Settings → *Phone tools in your own sessions* shows a ready-made command for **Claude Code** (`claude mcp add …`) and **Codex** (`codex mcp add …`) with a copy button. Run it in Termux once, and every session of that agent gets the `phone` tools, even without the bubble. The Codex command also saves Buddy's token as `BUDDY_TOKEN` in `~/.bashrc`, since Codex reads it from the environment.

## Using Buddy

The app has three tabs: **Chats**, **Sessions** and **Settings**.

- **Chats:** start one with **New chat** (Claude Code or Codex), tap a chat to open it, long-press to delete it. Several chats can work at the same time.
- **Models:** tap the chip under a chat's title to pick the model for that chat (Opus, Sonnet, Haiku, Fable, Codex's own list, or any model name). The conversation continues with the new model.
- **Photos and files:** tap the paperclip to take a photo, pick photos, or attach any file up to 25 MB.
- **Voice:** tap the mic to talk; it sends when you stop. The speaker button reads replies aloud.
- **Tool calls** fold into one "N tool calls" line you can open.
- **Approvals** appear in the chat and in the bubble: **Approve** or **Deny**.
- **Continue in the Claude app** (Claude Code chats): reopens the chat in a Termux tab with Remote Control on for that session only. Type `/exit` there when done.
- **Sessions:** every Claude Code and Codex session on the phone, from Buddy, a terminal or Claude Code's background service, with a Stop button. Recent sessions can be read, or continued in Buddy.
- **Settings:** setup, appearance (light/dark/system, accent colour, surface), default agent, the bubble, read-aloud and developer mode.

### When an agent is using your phone

As soon as an agent starts operating the phone, Buddy makes it obvious so you know not to touch it: the bubble turns into a solid **"Using your phone"** pill with a stop button (even if you've hidden the bubble), and a status-bar notification appears (a Live Update chip on Android 16 where supported). **Stop** ends Buddy's chats and refuses phone actions from any session for a minute.

### The bubble

| | |
|---|---|
| Tap the bubble | Open the chat panel |
| Spinning ring | An agent is working; the ■ button under the bubble stops it |
| Drag the bubble | Move it; it snaps to the nearest edge |
| Drag it onto the ✕ | Hide it. A notification brings it back. It never shows on the lock screen. |
| Back | Close the keyboard, then collapse the panel |
| **Chats** / tap the title | Switch chats, start a new one, or delete one |
| Long-press a message | Copy it |

## Developer mode: building apps

Off by default. When it's on, the agent may run Termux commands and create or edit files in `~/.buddy/work`. With the bundled **android-app** skill (plus `AGENTS.md` for Codex) you can ask *"make me a calculator app"*: it writes plain Java, builds a signed APK on the phone without Gradle, and installs it, asking before it installs.

Leave it off for everyday use: with it on, text on screen that tries to trick the agent could lead to commands being run.

## Security

An accessibility service is powerful. Please read this before installing.

- **Local only.** The MCP server listens on `127.0.0.1` and requires a random per-install token. Nothing is reachable from the network.
- **Approval gate.** The agent is instructed to call `confirm` before sending messages, posting, buying, deleting or changing settings. You see the exact action with **Approve / Deny**.
- **Prompt injection is the main risk.** Buddy reads whatever is on screen. A malicious message, email or web page could contain text like "send this to all your contacts". Buddy's instructions treat on-screen text as content, never commands, and the approval gate is the backstop, but no model is perfect. Read approval cards carefully.
- **Limited tools.** Outside developer mode, Claude Code gets the phone tools, web search and fetch, and read access to that chat's attachments only (no shell, no other files). Codex runs in its read-only sandbox.
- **What leaves the phone:** only what Claude Code or Codex sends to Anthropic or OpenAI to answer you, including screen text, screenshots and attachments it reads.
- **Never** let Buddy type passwords, one-time codes or payment details. It's instructed to hand those back to you.

Found a security problem? Please open a private security advisory on GitHub rather than a public issue.

## Build

### Android Studio / Gradle

```
./gradlew assembleRelease
```

Release signing reads `BUDDY_KEYSTORE`, `BUDDY_KEYSTORE_PASSWORD` (and optionally `BUDDY_KEY_ALIAS`, `BUDDY_KEY_PASSWORD`) from the environment; without them the APK is unsigned. GitHub Actions builds every push and attaches the APK to a release for `v*` tags.

### On the phone, in Termux (no Gradle)

```
pkg install openjdk-17 aapt2 d8 apksigner python
# android.jar from platform-36 at ~/android-sdk/android-36/android.jar
./build.sh        # → build/buddy-release.apk
```

`build.sh` runs aapt2 → javac → d8 → align → apksigner and creates a signing key at `~/.buddy-keys/release.jks` on first run. Back it up: updates must be signed with the same key.

The version lives in `app/src/main/java/app/buddy/assistant/BuildInfo.java`.

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/app/buddy/assistant/BuddyService.java` | Accessibility service: hosts the bubble, the MCP server and the in-control indicator |
| `.../Tools.java` | The phone tools |
| `.../McpServer.java` | Minimal MCP server (streamable HTTP, JSON responses) |
| `.../Bubble.java` | The floating bubble and its chat panel |
| `.../MainActivity.java`, `ChatActivity.java`, `MonitorActivity.java`, `SetupActivity.java` | Chats, a chat, Sessions, Settings |
| `.../OnboardingActivity.java` | First-run setup guide |
| `.../ChatHub.java`, `Sessions.java` | Chat state shared by the app and the bubble |
| `.../Ui.java`, `Theme.java` | Components and the light/dark/accent theme |
| `.../Termux.java`, `TermuxSetup.java` | Talks to Termux; builds the setup and install commands |
| `termux/buddy-run` | Runs the agent with the phone tools and streams events back |
| `termux/system-prompt.md` | Buddy's instructions for the agent |
| `app/src/main/assets/termux` | Symlink to `termux/`, so the helper ships inside the APK |

## License

[Apache-2.0](LICENSE). Geist fonts: SIL Open Font License. Icons: Material Symbols (Apache-2.0).
