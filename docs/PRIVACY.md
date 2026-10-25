# Buddy privacy policy

*Last updated: 22 October 2026*

Buddy is an open-source Android app (Apache 2.0) by Hamdi Ghorbel. It lets an AI agent of your choice (Claude Code, Codex, OpenCode, Antigravity or Grok) use your phone's apps for you. This policy explains what data Buddy handles. The source code is public at https://github.com/ghorbelhamdi/buddy-android, so every statement here can be checked.

## Buddy has no servers

Buddy doesn't run any servers or analytics. It doesn't collect, store, sell or share your data with us or with advertisers, and it shows no ads.

## Accessibility service

Buddy uses Android's AccessibilityService API so the agent can carry out what you ask: it reads what's on the screen and taps, types and scrolls in other apps, and it shows the floating bubble. Buddy explains this and asks for your agreement in the app before you turn the service on.

- The screen content is read only while the agent works on a request you made.
- That content, which can include personal information shown on screen (such as messages, names, addresses or order details), is sent to the AI service of the agent you chose, only to carry out your request (see below).
- Before anything that sends, posts, buys or deletes, Buddy shows the action and waits for your approval.
- You can turn the service off at any time in Android's accessibility settings.

## Notification access (notification rules)

Notification rules are optional. Buddy reads notifications only if you add a rule and give Buddy notification access in Android's settings; Buddy explains this and asks for your agreement in the app first.

- Buddy checks each new notification against your rules on your phone. Notifications that no rule matches are ignored right away: they are not stored and not sent anywhere.
- When a notification matches a rule, its app name, sender and text are sent to the agent you picked for that rule (and so to that agent's AI service, see below), together with your instruction, to carry out the rule.
- Buddy can answer through a notification's Reply button. It asks for your approval first, unless you gave the rule a countdown, in which case the reply is sent when the countdown ends unless you stop it.
- The rule's chat and a short activity list (time, app, sender and what was done) are kept on your phone.
- You can pause or delete rules in the app, and remove the access in Android's settings at any time.

## Data sent to the AI service you choose

The agent runs inside the app and talks directly to its own provider. What you type or say, the attachments you add, the screen content the agent reads, and the notifications your rules pass on are sent to:

- **Claude Code:** Anthropic, under your Claude account. See https://www.anthropic.com/legal/privacy
- **Codex:** OpenAI, under your ChatGPT account. See https://openai.com/policies/privacy-policy
- **OpenCode:** the provider of the model you pick (by default OpenCode's free models). See https://opencode.ai
- **Antigravity:** Google, under your Google account. See https://policies.google.com/privacy
- **Grok:** xAI, under your Grok account. See https://x.ai/legal/privacy-policy

You sign in with these services yourself; Buddy never sees your passwords. Their own privacy policies apply to what they receive.

## Voice

If you use the microphone, your voice is turned into text by the engine you choose in Settings: your phone's speech recognizer, Claude's dictation (Anthropic) or ChatGPT's (OpenAI). Replies read aloud use the phone's voice or ChatGPT's voices (OpenAI).

## Data stored on your phone

Your chats, settings, sign-ins and the agent's files are stored only in Buddy's private storage on your phone. Uninstalling Buddy deletes them. You can delete a chat at any time in the app.

## Downloads

To set up an agent, Buddy downloads a small Linux system from Alpine Linux (dl-cdn.alpinelinux.org) and the agent from its official source (Anthropic, OpenAI, Google, xAI or npm). These downloads don't include any of your personal data. The version of Buddy from GitHub also asks GitHub (api.github.com) about once a day whether a newer version is out; the version from Google Play is updated by Play.

## Permissions

- **Accessibility:** described above.
- **Microphone:** only when you tap the mic.
- **Notifications:** for task status and approvals.
- **Notification access:** only for notification rules, described above.
- **Keep awake (wake lock):** while a notification rule's task runs, so it can finish with the screen off.
- **Internet:** to reach the AI service you chose and the downloads above.

## Children

Buddy is not directed at children under 13.

## Changes and contact

Changes to this policy are published in this file, with their history in the repository. Questions: open an issue at https://github.com/ghorbelhamdi/buddy-android/issues or email duoboard.support@gmail.com.
