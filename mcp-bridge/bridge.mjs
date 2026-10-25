#!/usr/bin/env node
// Buddy's phone tools over stdio: a small MCP server that passes each request on to Buddy's own MCP server on the
// phone (http://127.0.0.1:8765/mcp), for MCP clients that start servers as commands instead of connecting to a URL.
// No dependencies. The token is the one Buddy shows in Settings → Phone tools in your own sessions.
//
//   BUDDY_TOKEN=<token> node bridge.mjs        (BUDDY_URL overrides the address)
//
// When Buddy can't be reached (no phone, Buddy not running, a wrong token), the tool list comes from tools.json, a
// copy of Buddy's, and each tool call says why it couldn't run.

import { readFileSync } from "node:fs";
import { createInterface } from "node:readline";

const URL_ = process.env.BUDDY_URL || "http://127.0.0.1:8765/mcp";
const TOKEN = (process.env.BUDDY_TOKEN || "").replace(/^Bearer\s+/i, "");
const TIMEOUT_MS = 10 * 60_000; // an approval card can wait for the user
const TOOLS = JSON.parse(readFileSync(new URL("./tools.json", import.meta.url), "utf8"));

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

// Buddy's answer to one JSON-RPC request; throws when Buddy can't be reached
async function buddy(method, params) {
  const r = await fetch(URL_, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      ...(TOKEN ? { Authorization: "Bearer " + TOKEN } : {}),
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  if (r.status === 401 || r.status === 403) throw new Error("Buddy refused the token: set BUDDY_TOKEN to the one in Buddy's Settings");
  if (!r.ok) throw new Error("Buddy answered HTTP " + r.status);
  const text = await r.text();
  const json = text.trimStart().startsWith("{") ? text : (text.match(/^data: (.*)$/m) || [])[1]; // JSON or one SSE event
  const m = JSON.parse(json);
  if (m.error) throw Object.assign(new Error(m.error.message), { rpc: m.error });
  return m.result;
}

async function handle(m) {
  const reply = (result) => send({ jsonrpc: "2.0", id: m.id, result });
  const fail = (code, message) => send({ jsonrpc: "2.0", id: m.id, error: { code, message } });
  switch (m.method) {
    case "initialize":
      return reply({
        protocolVersion: m.params?.protocolVersion || "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: { name: "buddy-phone-bridge", version: "1.0.0" },
        instructions: "Buddy's phone tools (read the screen, tap, type, open apps…) on an Android phone running Buddy.",
      });
    case "ping":
      return reply({});
    case "tools/list":
      try {
        return reply(await buddy("tools/list", m.params || {}));
      } catch {
        return reply({ tools: TOOLS });
      }
    case "tools/call":
      try {
        return reply(await buddy("tools/call", m.params));
      } catch (e) {
        if (e.rpc) return fail(e.rpc.code, e.rpc.message);
        const why = e.name === "TimeoutError" ? "Buddy didn't answer in time" : e.message;
        return reply({
          isError: true,
          content: [{ type: "text", text: `Couldn't use Buddy at ${URL_}: ${why}. Buddy must be running on this phone, with its accessibility service on.` }],
        });
      }
    default:
      if (m.id === undefined) return; // a notification (initialized, cancelled): nothing to answer
      return fail(-32601, "Method not found: " + m.method);
  }
}

createInterface({ input: process.stdin }).on("line", (line) => {
  if (!line.trim()) return;
  let m;
  try {
    m = JSON.parse(line);
  } catch {
    return send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } });
  }
  handle(m).catch((e) => send({ jsonrpc: "2.0", id: m.id ?? null, error: { code: -32603, message: String(e.message || e) } }));
});
