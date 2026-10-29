# Buddy phone bridge (stdio)

Buddy's phone tools (read the screen, tap, type, scroll, open apps, screenshots, notifications, the background browser…) are a local MCP server inside the Buddy app on your Android phone: `http://127.0.0.1:8765/mcp`. Most agents connect to it directly; Buddy's **Settings → Phone tools in your own sessions** gives the command for Claude Code, Codex, OpenCode, Antigravity and Grok.

This bridge is for MCP clients that can only start a server as a command. It speaks MCP over stdio and passes every request on to Buddy. It has no dependencies (Node 18+).

```bash
BUDDY_TOKEN=<token from Buddy's Settings> npx -y buddy-phone-bridge
```

Or from a copy of this folder: `BUDDY_TOKEN=<token> node bridge.mjs`.

MCP client config:

```json
{
  "mcpServers": {
    "phone": {
      "command": "npx",
      "args": ["-y", "buddy-phone-bridge"],
      "env": { "BUDDY_TOKEN": "<token from Buddy's Settings>" }
    }
  }
}
```

- `BUDDY_TOKEN`: the token Buddy shows (with or without `Bearer `).
- `BUDDY_URL`: Buddy's address, if not `http://127.0.0.1:8765/mcp`.

Run it on the phone itself (Termux, for example): Buddy only listens on `127.0.0.1`. When Buddy can't be reached, the bridge still lists the tools (from `tools.json`, a copy of Buddy's list) and each call says why it couldn't run; that's also what happens in the Dockerfile's container, which has no phone.

You need the Buddy app on the phone: https://github.com/ghorbelhamdi/buddy-android

Buddy is an independent project, not made by or affiliated with Anthropic, OpenAI, Google or xAI.
