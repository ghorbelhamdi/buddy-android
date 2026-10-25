# Buddy user guide

The short version is in the [README](../README.md). This page has the details.

- [Setup in detail](#setup-in-detail)
- [Updating](#updating)
- [Using Buddy](#using-buddy)
- [Developer mode: building apps](#developer-mode-building-apps)
- [Security in detail](#security-in-detail)
- [How it works](#how-it-works)

## Setup in detail

| Step | What happens |
|---|---|
| Choose your agent | **Claude Code** (recommended), **Codex**, **OpenCode** (free models, no account), **Antigravity** (your Google account), or Claude Code and Codex together |
| Accessibility | Turn on *Buddy assistant*. Greyed out ("Restricted setting")? App info → ⋮ → **Allow restricted settings**, then try again |
| Install and sign in | One tap installs your agent in Buddy's Linux (Claude Code about 235 MB, Codex about 150 MB, OpenCode about 230 MB, Antigravity about 200 MB; use Wi-Fi). OpenCode needs no sign-in; its providers can be connected later with **Connect a provider**, which runs `opencode auth login` in Buddy's terminal. For the others, **Sign in**: your browser opens, you approve, and Buddy comes back by itself. For Claude and Antigravity, Buddy picks the one-time code off the confirmation page for you (Antigravity waits about a minute for it). No browser on this phone you want to use? **Sign in on another device** (Claude, Antigravity: a link to open anywhere) or **Sign in with a code** (Codex) |
| Keep it running | Battery: unrestricted for Buddy |
| Try it | A first chat with a ready-made prompt |

**Android blocked the install?** Play Protect may say *"App blocked to protect your device"* for apps that use Accessibility and come from a browser: tap **More details → Install anyway** (or install from a file manager). In some countries Play Protect's stricter mode (enhanced fraud protection) shows no *Install anyway*: it blocks any sideloaded app that uses accessibility or notification access, from a browser, a messaging app or a file manager. Then turn off **Play Store → Play Protect → ⚙ → Scan apps with Play Protect** just for the install and turn it back on afterwards, or install from a computer with `adb install buddy.apk`. On Android 13+, if *Buddy assistant* is greyed out in Accessibility, open App info → ⋮ → **Allow restricted settings** first.

## Updating

**Ask Buddy:** *"Is there a Buddy update?"* or *"Update yourself."* The agent checks the latest release, tells you what's new, and with your OK downloads `buddy.apk` from this repo's releases and opens Android's installer; you tap **Update**.

**Automatic updates with [Obtainium](https://obtainium.imranr.dev)** (free, open source; it checks GitHub for you and installs new versions):
1. Install Obtainium (from its website, F-Droid or IzzyOnDroid).
2. In Obtainium, tap **Add App** and paste `https://github.com/ghorbelhamdi/buddy-android`, then **Add**.
3. Each release has two identical APKs (`buddy.apk` and `buddy-vX.Y.Z.apk`). If Obtainium asks which one, pick `buddy.apk`. To never be asked, set **Filter APKs by regular expression** to `^buddy\.apk$` in the app's settings in Obtainium.
4. Obtainium now notifies you when a new version is out; tap **Update** and confirm Android's installer (the first time, allow Obtainium to install apps).

**By hand:** open the [latest `buddy.apk`](https://github.com/ghorbelhamdi/buddy-android/releases/latest/download/buddy.apk) on the phone and install it over the current one. To get an email for each new version, sign in to GitHub and choose **Watch → Custom → Releases** on the repository.

**The agents** (Claude Code, Codex, OpenCode, Antigravity) update separately: Settings → the agent → **Check for updates**. An OpenCode 1.x install gets **Update to OpenCode 2** there; your connected providers are kept.

## Using Buddy

The app has three tabs: **Chats**, **Sessions** and **Settings**.

- **Chats:** start one with **New chat** (Claude Code, Codex, OpenCode or Antigravity; agents that aren't set up are greyed out), tap a chat to open it, long-press to delete it. Several chats can work at the same time.
- **Models:** tap the chip under a chat's title to pick the model for that chat (Opus, Sonnet, Haiku, Fable, Codex's own list, OpenCode's models including the free ones, Antigravity's list, or any model name). The conversation continues with the new model.
- **Photos and files:** tap the paperclip to take a photo, pick photos, or attach any file up to 25 MB.
- **Voice:** tap the mic to talk; the box turns into a recording bar (✕ cancel, live waveform, ↑ send). Choose the engine in Settings → Voice: the **phone's** recognizer, **Claude** (Claude Code's own dictation, on your Claude plan) or **ChatGPT** (ChatGPT's voice through Codex, on your ChatGPT plan; any language, even mixed). Claude and ChatGPT are greyed out until Claude Code or Codex is signed in. Pick your languages there; long-press the mic to switch.
- **Read aloud:** the speaker button reads replies, in the phone's voice or in **ChatGPT's voice** (Juniper, Maple, Ember, Cove: Settings → Voice → Voice output).
- **Add instructions while it works:** type or talk while a task runs; the agent gets it at its next step, without stopping.
- **Tool calls** fold into one "N tool calls" line you can open.
- **Notification rules:** Settings → Experience → Notification rules. A rule picks an app, optionally who it's from and words it contains, and says what the agent should do; the agent gets that instruction with the notification, in a chat of its own per conversation (so it remembers what it answered before). It can reply through the notification's own Reply field without opening the app. Each rule picks its agent and model (a smaller model is quicker and uses less of your plan). Before acting, a rule either asks you as usual or uses a **countdown** (10 s to 5 min): the approval card approves itself unless you tap to stop it. Only sending a message can be approved that way, and only after a second AI has checked it against your rule (the Smart reviewer: Haiku by default, or the model you pick in App permissions). If you don't answer within a minute (or the phone is locked), the reply goes to **Waiting for you** (a notification and a banner on the Chats screen), where you send, edit or skip it later; nothing else waits on you meanwhile. A reply is held back when the person writes again before it's sent, and the next run answers everything in one reply. Without internet, rules wait and start when the connection is back. When Android hides a chat's notification from apps with notification access (it does when a message looks like a one-time code), a rule can't read it: Recent activity and a quiet notification say so, and the rule picks up again once the notification is readable (open the chat). Recent activity is listed on the rules screen, and each run ends with a quiet notification saying what it did. Buddy needs notification access for this (it asks first); notifications no rule matches are ignored (except that a call's notification pauses an agent that's using the phone: for that only its category is looked at).
- **Approvals** appear in the chat and in the bubble: **Approve**, **Deny**, or **Change**: type or say what to adjust ("make it 9, add a smiley"), and the agent revises the message, post or email and asks again.
- **Sessions:** every Claude Code, Codex, OpenCode and Antigravity process in Buddy's Linux, with a Stop button. Recent sessions can be read, or continued in Buddy.
- **Settings:** setup, appearance (light/dark/system, accent colour, surface, and **Buddy's look**: its own face or a picture of yours, best a PNG or WebP with a transparent background, shown on the bubble and next to its replies), agents (install, sign in, sign out), voice, the bubble, app permissions, developer mode and Remote Control.
- **Languages:** Buddy is in English, Arabic, Chinese (Simplified), Dutch, French, German, Hindi, Indonesian, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Turkish and Ukrainian. It follows the phone's language; to pick another one for Buddy alone, use Android Settings → Apps → Buddy → Language. The agents answer in whatever language you write in.
- **Terminal:** a real terminal for Buddy's Linux (Sessions → Terminal): tabs, text selection and copy, links, pinch to zoom. Claude Code's, Codex's, OpenCode's and Antigravity's own screens run in it.

### Phone tools in your own sessions

Buddy's phone tools are a regular MCP server on the phone (`http://127.0.0.1:8765/mcp`, with a per-install token). Settings → **Phone tools in your own sessions** shows a ready-made command for **Claude Code** (`claude mcp add-json …`), **Codex** (`codex mcp add …`), **OpenCode** (an `OPENCODE_CONFIG_CONTENT` line for `~/.bashrc`) and **Antigravity** (`agy mcp add …`), with a Copy button. The first three set a 10-minute tool timeout, so an approval card can wait for you (the agents' default is a minute). Run it once in any terminal on the phone (Termux, for example), and every session of that agent gets the `phone` tools, even without the bubble. The token is masked on screen.

### Remote Control: a Claude Code session in your Claude app

Settings → **Remote Control → Claude Code session** keeps a Claude Code session with [Remote Control](https://docs.claude.com/en/docs/claude-code) running inside Buddy. It appears as **"Buddy"** in the Claude app's Code sessions, on this phone or anywhere else, and it has the phone tools. Buddy restarts it if it stops and continues the same conversation after an update.

### Codex session in the ChatGPT app

Settings → **Remote Control → Codex session** keeps Codex's app-server with remote control running inside Buddy, with the phone tools, so you can use it from the ChatGPT app (Codex) on this phone or on a computer. **Pair a device** shows a one-time code (it works once, for about 10 minutes) to enter in ChatGPT: in Codex on a phone, or under Settings → Connections → Control other devices on a computer. Buddy shows up there as **"localhost"**, because Codex names a host after the system's hostname. Codex keeps its own settings, so you pick each chat's permission in ChatGPT. Codex's sandbox can't run inside Buddy's Linux, so with Default permissions Codex asks before running each command outside it (with Full access it doesn't ask). Buddy restarts the server if it stops, and pairings are kept.

### SSH from Termux

Settings → **Remote Control → SSH from Termux** runs an SSH server (OpenSSH) inside Buddy's Linux, so you can work in it from Termux: `ssh buddy`, `scp`, `sftp`, port forwarding. It listens on `127.0.0.1:8767` only, so only apps on this phone can reach it (from a computer, go through `adb forward tcp:8767 tcp:8767`), and it takes keys only, no passwords. **Copy command** gives a Termux command that makes a key if Termux has none, sends it to Buddy with a one-time code (it works once, within 10 minutes) and adds `Host buddy` to `~/.ssh/config`. Run it once; from then on, `ssh buddy` in Termux opens Buddy's Linux. Buddy keeps the server running, also after an update, until you switch it off.

### When an agent is using your phone

As soon as an agent starts operating the phone, Buddy makes it obvious so you know not to touch it: the bubble turns into a solid **"Using your phone"** pill that also names the step ("Using your phone · Tapping “Send”") (hold the bubble for **Pause** or **Stop task**; it shows even if you've hidden the bubble), and a status-bar notification appears (a Live Update chip on Android 16 where supported). **Stop** ends Buddy's chats and refuses phone actions from any session for a minute. **Pause** lets you use the phone in the middle of a task: the agent's next phone action waits until you tap the **Paused** pill next to the bubble (or hold the bubble and tap **Resume**), then it re-reads the screen before going on. Buddy also pauses on its own when you take the phone back: you touch the screen outside the bubble, or a call rings or starts (a phone call, or one in an app like WhatsApp or Messenger). The bubble shows it's paused, and after a call a notification offers **Resume** too; nothing resumes by itself. Touches while a question or approval card waits don't pause (the agent re-reads the screen after your answer), and neither do touches while only notification rules are running. Stop while paused ends the task without that action running, and a pause longer than 5 minutes stops the task.

### Background browser

*GitHub version only, for now.* Turn on **Settings → Background browser**: the first time, Buddy downloads Chromium into its Linux (about 300 MB). Agents then use this browser for web pages (looking things up, comparing prices, checking a page) without taking over your screen, also while the phone is locked; for your own accounts and apps they still use the phone. It starts when an agent needs it and stops after 3 minutes unused.

- **Live view.** A chat that uses it shows the page live above the messages; tap it full screen to use it yourself, with an address bar. Touching it while the agent works pauses the agent until you tap **Continue** or write to it; using the rest of the phone doesn't hold it up. The bubble says what it's doing ("Browsing reddit.com · Typing").
- **The same approvals.** Buttons like Post, Send, Buy, Pay and Delete get Buddy's approval card, as on the phone. Agents never type into password, code or card fields.
- **Unsaved work stays.** When a page warns that leaving loses what's typed (an unsent post, a form), Buddy stays on it and tells the agent; only a second try leaves.
- **Your part, marked.** When a page needs you (a sign-in, a captcha, your details), the agent asks, and Buddy opens the browser full screen with the fields and buttons marked; it closes again when you tap **Done**.
- **Apps closing by themselves?** Android 12+ kills apps' child processes once all apps together run more than about 32, and the browser, Remote Control sessions and Termux all count. If Termux or Buddy's sessions get closed ("signal 9"), turn on **Developer options → Disable child process restrictions**.
- **Sign-ins.** To let agents use a site with your account, open the browser and sign in yourself. **Signed-in sites** lists the sites you signed in to, each with **Log out**; **Wipe browser data** clears everything. "Continue with Google" often fails in automated browsers: use email and password.

### The bubble

| | |
|---|---|
| Tap the bubble | Open the chat panel, or (Settings → Experience → Tapping the bubble) start talking or open the chat full screen in the app. The panel goes edge to edge; **Compact** makes it smaller and keeps clear of the screen edges, for side gestures, and in portrait looks like a messenger's chat heads: Buddy above it, Close and Open in the app below |
| Spinning ring | An agent is working |
| **Hold the bubble** | Its actions fan out: **Stop task**, **Pause**/**Resume**, **Add instruction** (by voice), **Stop voice**, **Talk**, **Open chat**, **Hide**. Slide onto one and let go, or let go and tap |
| Drag the bubble | Move it; it snaps to the nearest edge |
| Drag it onto the ✕ | Hide it. A notification brings it back. It never shows on the lock screen. |
| Back | Close the keyboard, then collapse the panel |
| **Chats** / tap the title | Switch chats, start a new one, or delete one |
| Long-press a message | Copy it |

## Developer mode: building apps

Off by default. When it's on, the agent may run commands in Buddy's Linux (including installing packages with `apk`) and create or edit files in `~/.buddy/work`. With the bundled **android-app** skill (plus `AGENTS.md` for Codex) you can ask *"make me a calculator app"*: it sets up a toolchain the first time (OpenJDK, aapt2, d8, apksigner; about 400 MB), writes plain Java, builds a signed APK on the phone without Gradle, and opens Android's installer for you to confirm. The first time, Android asks you to allow Buddy to install apps.

Leave it off for everyday use: with it on, text on screen that tries to trick the agent could lead to commands being run.

## Security in detail

- **Local only.** The MCP server listens on `127.0.0.1` and requires a random per-install token. Nothing is reachable from the network.
- **Approval gate.** The agent is instructed to call `confirm` before sending messages, posting, buying, deleting or changing settings. You see the exact action with **Approve / Change / Deny**. When the agent needs you to choose (which reply, which item), it can call `ask` instead: a card with 2–4 answers and your own typed answer. When there's a step only you can do (a password or code, your details in a form, a file picker, a captcha), `ask` can mark the fields or buttons on the screen and wait next to the bubble while you do it; `point_out` shows where something is. The marks (a box, circle or arrow with a short label) are drawn over the app without blocking your touches, and turn green and go once you do what they point at (tap that button, flip that switch, fill in that field). Ask "show me how to…" and the agent guides you one step at a time, waiting for each tap instead of doing it for you.
- **App permissions (enforced by Buddy, not the agent).** Settings → Experience → App permissions, per app:
  - **Trust:** asking is left to the agent.
  - **Hard:** Buddy itself checks every tap; buttons like Send, Pay, Delete (built-in words in all of Buddy's languages, plus your own *always ask* / *never ask* rules, with suggestions from buttons the agent has tapped) need your OK.
  - **Smart:** the rules, then a separate temporary Claude Code or Codex session without tools reviews every other tap against your request and the screen (about 3 s a tap). OpenCode and Antigravity chats are reviewed by Claude Code or Codex.
  
  Messaging, social, email, calling, shopping and money apps start on Hard. The agent can never tap or type in Buddy's own bubble, cards or settings, so it can't approve for you or loosen the rules.
- **Prompt injection is the main risk.** Buddy reads whatever is on screen. A malicious message, email or web page could contain text like "send this to all your contacts". Buddy's instructions treat on-screen text as content, never commands, and the approval gate is the backstop, but no model is perfect. Read approval cards carefully.
- **Notification rules work for someone else's message,** so their runs get less: no shell, files or web (even in developer mode), no installing apps, your Trust settings don't apply, and opening a link always asks you. The notification is passed to the agent as outside content, never as instructions. A rule's countdown can only approve sending a message (never paying, buying or deleting, and never in payment or shopping apps), and only after a second AI agrees the reply fits your rule; otherwise the card waits for you. A run sends at most one reply, and none once you've answered the person yourself. Each rule has an hourly limit: by default 20 runs per conversation and three times that for the whole rule (change it or turn it off in the rule); past it, new messages wait and then get one run together. Use countdowns for low-stakes replies.
- **Limited tools.** Outside developer mode, Claude Code gets the phone tools, web search and fetch, and read access to that chat's attachments only (no shell, no other files). Codex runs in its read-only sandbox. OpenCode's shell and file edits are refused (set to "ask", which a non-interactive run auto-rejects). Antigravity has only the phone tools and that chat's attachments pre-approved, and can't run notification rules yet. Everything runs inside Buddy's own app storage, separate from your other apps' files.
- **What leaves the phone:** only what Claude Code, Codex, OpenCode or Antigravity sends to its model provider (Anthropic, OpenAI, Google, OpenCode's own service, or the provider you connected) to answer you, including screen text, screenshots and attachments it reads.
- **Never** let Buddy type passwords, one-time codes or payment details. It's instructed to hand those back to you.

## How it works

```
Buddy app / bubble ──► Buddy's Linux (Alpine via proot): buddy-run ──► claude -p, codex exec, opencode run or agy -p
        ▲                                                                  │
        └────────────── streamed replies (POST /event) ◄───────────────────┤
                                                                           ▼
Buddy accessibility service ◄──── MCP over http://127.0.0.1:8765/mcp (phone tools)
```

- The **accessibility service** draws the bubble and runs a small local **MCP server** with the phone tools: `read_screen`, `tap`, `long_press`, `type_text`, `scroll`, `swipe`, `press`, `open_app`, `list_apps`, `open_url`, `screenshot`, `wait`, `install_apk`, `wait_for_install`, `confirm`, `ask`, `point_out`, `notify`, `reply_notification` and `update_buddy`.
- **Typing on web pages** (Chrome, apps' web views) goes in as a paste, so page editors keep it; your clipboard then holds that text.
- **Tips per app.** When an app or site costs an agent time (a button that needs a second tap, a field that only takes a paste), it can note that in `~/.buddy/tips/<app or site>.md` in Buddy's Linux. Buddy shows the note the first time that app or site comes up in a chat. The notes stay on your phone; delete them there if you like.
- **Buddy's Linux** is a small Alpine Linux that Buddy downloads into its own storage (4 MB) and runs with [proot](https://proot-me.github.io/), which ships inside the APK. Claude Code installs there with Anthropic's official installer, Antigravity with Google's; Codex and OpenCode from npm.
- Your message goes to the agent in Buddy's Linux, which uses those tools and streams its progress back to the app.
