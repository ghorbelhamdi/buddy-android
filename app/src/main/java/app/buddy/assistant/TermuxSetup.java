package app.buddy.assistant;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds the commands the user pastes into Termux. The setup command carries the helper files
 * bundled in this APK (assets/termux/), so it needs no download and always matches the app version.
 */
final class TermuxSetup {
    private TermuxSetup() {
    }

    static String command(Context c) throws IOException {
        String script = "set -e\n"
                + "echo 'Setting up the Buddy helper…'\n"
                + "for p in jq curl; do command -v $p >/dev/null || pkg install -y $p; done\n"
                + "mkdir -p \"$HOME/bin\" \"$HOME/.buddy/work\" \"$HOME/.termux\"\n"
                + "echo '" + b64(asset(c, "termux/buddy-run")) + "' | base64 -d > \"$HOME/bin/buddy-run\"\n"
                + "chmod 700 \"$HOME/bin/buddy-run\"\n"
                + "echo '" + b64(asset(c, "termux/system-prompt.md")) + "' | base64 -d > \"$HOME/.buddy/system-prompt.md\"\n"
                + "SK=\"$HOME/.buddy/work/.claude/skills/android-app\"; mkdir -p \"$SK\"\n"
                + "echo '" + b64(asset(c, "termux/android-app/SKILL.md")) + "' | base64 -d > \"$SK/SKILL.md\"\n"
                + "echo '" + b64(asset(c, "termux/android-app/build-apk.sh")) + "' | base64 -d > \"$SK/build-apk.sh\"\n"
                + "echo '" + b64(asset(c, "termux/android-app/setup-toolchain.sh")) + "' | base64 -d > \"$SK/setup-toolchain.sh\"\n"
                + "chmod 700 \"$SK/build-apk.sh\" \"$SK/setup-toolchain.sh\"\n"
                + "echo '" + b64(asset(c, "termux/AGENTS.md")) + "' | base64 -d > \"$HOME/.buddy/work/AGENTS.md\"\n"
                + "P=\"$HOME/.termux/termux.properties\"; touch \"$P\"\n"
                + "if grep -q '^[[:space:]]*allow-external-apps' \"$P\"; then\n"
                + "  sed -i 's/^[[:space:]]*allow-external-apps.*/allow-external-apps = true/' \"$P\"\n"
                + "else printf '\\nallow-external-apps = true\\n' >> \"$P\"; fi\n"
                + "termux-reload-settings 2>/dev/null || true\n"
                + "echo 'Done. Buddy will come back by itself in a few seconds.'\n";
        return "echo '" + b64(script.getBytes(StandardCharsets.UTF_8)) + "' | base64 -d | bash";
    }

    /** Community installer for Claude Code on Termux (glibc-patched official binary), then sign in. */
    static final String CLAUDE_INSTALLER =
            "https://raw.githubusercontent.com/ferrumclaudepilgrim/claude-code-android/main/install.sh";

    static String installClaude() {
        return "curl -fsSL " + CLAUDE_INSTALLER + " -o ~/claude-install.sh && bash ~/claude-install.sh && claude";
    }

    static String signInClaude() {
        return "claude";
    }

    /** Codex from npm, plus Buddy's wrapper (DNS/TLS paths via proot), then sign in with ChatGPT. */
    static String installCodex(Context c) throws IOException {
        String script = "set -e\n"
                + "echo 'Installing Codex…'\n"
                + "pkg install -y nodejs-lts proot\n"
                + "npm install -g @openai/codex\n"
                + "mkdir -p \"$HOME/bin\"\n"
                + "echo '" + b64(asset(c, "termux/codex")) + "' | base64 -d > \"$HOME/bin/codex\"\n"
                + "chmod 700 \"$HOME/bin/codex\"\n"
                + "grep -q 'HOME/bin' \"$HOME/.bashrc\" 2>/dev/null || echo 'export PATH=$HOME/bin:$PATH' >> \"$HOME/.bashrc\"\n"
                + "echo 'Codex installed. Sign in with your ChatGPT account next.'\n";
        return "echo '" + b64(script.getBytes(StandardCharsets.UTF_8)) + "' | base64 -d | bash && ~/bin/codex login";
    }

    static String signInCodex() {
        return "~/bin/codex login";
    }

    private static byte[] asset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }
}
