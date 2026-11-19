# Buddy phone bridge (stdio)

**This needs the free Buddy app on your Android phone: [download the APK](https://github.com/ghorbelhamdi/buddy-android/releases/latest/download/buddy.apk) or see [Buddy on GitHub](https://github.com/ghorbelhamdi/buddy-android).** Buddy lets AI agents read your screen, tap and type, and asks before anything risky. No root, no adb.

Buddy's phone tools (read the screen, tap, type, scroll, open apps, screenshots, notifications, the background browser…) are an MCP server inside the Buddy app on your Android phone. This bridge speaks MCP over stdio and passes every request on to Buddy, for any MCP client that starts servers as a command. It has no dependencies (Node 18+).

## From a computer (over Tailscale)

With the phone and the computer on the same tailnet, switch on **Settings → Phone tools in your own sessions → Phone tools on Tailscale** in Buddy. It shows the phone's address. Then:

```bash
claude mcp add -s user phone -e BUDDY_URL=http://<phone's Tailscale address>:8765 -- npx -y buddy-phone-bridge
```

On Windows (not WSL), Claude Code starts npx through cmd: `... -- cmd /c npx -y buddy-phone-bridge`.

Or in any MCP client's config:

```json
{
  "mcpServers": {
    "phone": {
      "command": "npx",
      "args": ["-y", "buddy-phone-bridge"],
      "env": { "BUDDY_URL": "http://<phone's Tailscale address>:8765" }
    }
  }
}
```

The first time the agent uses a phone tool, the phone asks you to pair this computer, on a card that names it and its Tailscale address. Approve it and the bridge keeps the key it gets in your settings folder (`~/.config/buddy-phone-bridge/keys.json`, or `%APPDATA%\buddy-phone-bridge\keys.json` on Windows), so later starts need no card. Remove the computer in Buddy's Settings and its key stops working: the next call asks to pair again.

## On the phone itself (Termux)

```bash
BUDDY_TOKEN=<token from Buddy's Settings> npx -y buddy-phone-bridge
```

Most agents on the phone connect to `http://127.0.0.1:8765/mcp` directly instead: Buddy's Settings give the command for Claude Code, Codex, OpenCode, Antigravity and Grok.

## Settings

- `BUDDY_URL`: Buddy's address, `http://127.0.0.1:8765` if not set (with or without `/mcp`).
- `BUDDY_TOKEN`: a token to use instead of pairing (with or without `Bearer `).

When Buddy can't be reached, the bridge still lists the tools (from `tools.json`, a copy of Buddy's list) and each call says why it couldn't run; that's also what happens in the Dockerfile's container, which has no phone.

Buddy is an independent project, not made by or affiliated with Anthropic, OpenAI, Google or xAI.
