package app.buddy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Buddy's terminal emulator: turns the program's output (UTF-8 text with VT100/xterm escape sequences)
 * into a grid of styled cells. Covers what shells, vim, htop and Claude Code use: cursor movement,
 * scroll regions, the alternate screen, 16/256/true colours, line drawing, wide characters and the
 * usual status queries. The view draws {@link #line(int)}; input goes back through {@link Host#write}.
 */
final class TermEmulator {
    interface Host {
        /** Bytes to send to the program (answers to queries). */
        void write(byte[] b);

        void onTitle(String title);

        void onBell();
    }

    // ---------------------------------------------------------------- cells and styles
    // Colour: 0 = default, 1..256 = palette index + 1, TRUE | 0xRRGGBB = true colour.
    static final int TRUE = 0x2000000;
    static final long BOLD = 1L << 52, DIM = 1L << 53, ITALIC = 1L << 54, UNDERLINE = 1L << 55,
            BLINK = 1L << 56, INVERSE = 1L << 57, INVISIBLE = 1L << 58, STRIKE = 1L << 59;
    private static final long COLOR_MASK = 0x3FFFFFFL;

    static int fg(long style) {
        return (int) (style & COLOR_MASK);
    }

    static int bg(long style) {
        return (int) ((style >>> 26) & COLOR_MASK);
    }

    private static long withFg(long s, int c) {
        return (s & ~COLOR_MASK) | (c & COLOR_MASK);
    }

    private static long withBg(long s, int c) {
        return (s & ~(COLOR_MASK << 26)) | ((long) (c & COLOR_MASK) << 26);
    }

    /** Second half of a wide character. */
    static final int WIDE_TAIL = -1;

    static final class Line {
        int[] cp;    // code point per cell, 0 = blank
        long[] st;   // style per cell
        boolean wrapped; // continued on the next line (autowrap)

        Line(int cols, long style) {
            cp = new int[cols];
            st = new long[cols];
            Arrays.fill(st, style & (COLOR_MASK << 26)); // blank cells keep only the background colour
        }

        void clear(int from, int to, long style) {
            long blank = style & (COLOR_MASK << 26);
            for (int i = Math.max(0, from); i < Math.min(to, cp.length); i++) {
                cp[i] = 0;
                st[i] = blank;
            }
        }

        void resize(int cols) {
            if (cols == cp.length) return;
            cp = Arrays.copyOf(cp, cols);
            st = Arrays.copyOf(st, cols);
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (int c : cp) {
                if (c == WIDE_TAIL) continue;
                sb.appendCodePoint(c == 0 ? ' ' : c);
            }
            int end = sb.length();
            while (end > 0 && sb.charAt(end - 1) == ' ') end--;
            return sb.substring(0, end);
        }
    }

    private static final int HISTORY = 3000;

    int rows, cols;
    private Line[] main, alt, screen;
    private final ArrayDeque<Line> history = new ArrayDeque<>();
    private boolean onAlt;

    // cursor and state
    int cx, cy;
    private boolean wrapPending;
    private long style;
    private int top, bottom; // scroll region, inclusive
    private boolean[] tabs;
    boolean appCursor, appKeypad, bracketedPaste, cursorVisible = true, mouseTracking;
    private boolean autowrap = true, originMode, insertMode, newlineMode;
    private char g0 = 'B', g1 = 'B';
    private boolean shifted; // SO: G1 active
    private int lastChar = ' ';
    String title = "";

    private static final class Saved {
        int x, y;
        long style;
        boolean origin, autowrap;
        char g0, g1;
        boolean shifted;
    }

    private final Saved savedMain = new Saved(), savedAlt = new Saved();

    private final Host host;
    /** Bumped on every change, so the view knows when to redraw. */
    volatile int version;

    TermEmulator(int rows, int cols, Host host) {
        this.host = host;
        this.rows = Math.max(1, rows);
        this.cols = Math.max(1, cols);
        main = newScreen(this.rows, this.cols);
        alt = newScreen(this.rows, this.cols);
        screen = main;
        top = 0;
        bottom = this.rows - 1;
        resetTabs();
    }

    private Line[] newScreen(int r, int c) {
        Line[] s = new Line[r];
        for (int i = 0; i < r; i++) s[i] = new Line(c, 0);
        return s;
    }

    private void resetTabs() {
        tabs = new boolean[cols];
        for (int i = 8; i < cols; i += 8) tabs[i] = true;
    }

    // ---------------------------------------------------------------- reading for the view

    synchronized int historySize() {
        return onAlt ? 0 : history.size();
    }

    boolean onAltScreen() {
        return onAlt;
    }

    /** Row from -historySize() (oldest) to rows-1. Callers synchronize on this emulator while drawing. */
    Line line(int row) {
        if (row >= 0) return screen[Math.min(row, rows - 1)];
        int idx = history.size() + row;
        if (idx < 0 || onAlt) return null;
        // ArrayDeque has no index access; history is small enough to walk from the nearer end
        if (idx < history.size() / 2) {
            int i = 0;
            for (Line l : history) if (i++ == idx) return l;
        } else {
            int i = history.size() - 1;
            java.util.Iterator<Line> it = history.descendingIterator();
            while (it.hasNext()) {
                Line l = it.next();
                if (i-- == idx) return l;
            }
        }
        return null;
    }

    synchronized String screenText() {
        StringBuilder sb = new StringBuilder();
        for (Line l : screen) sb.append(l.text()).append('\n');
        return sb.toString().replaceAll("\n+$", "\n");
    }

    // ---------------------------------------------------------------- resize

    synchronized void resize(int newRows, int newCols) {
        newRows = Math.max(1, newRows);
        newCols = Math.max(1, newCols);
        if (newRows == rows && newCols == cols) return;
        for (Line l : main) l.resize(newCols);
        for (Line l : alt) l.resize(newCols);
        for (Line l : history) l.resize(newCols);
        cols = newCols; // before resizeRows, which sizes its lines by it
        // rows: keep the cursor on screen by moving lines into history (main) or dropping them (alt)
        main = resizeRows(main, newRows, true);
        alt = resizeRows(alt, newRows, false);
        screen = onAlt ? alt : main;
        rows = newRows;
        top = 0;
        bottom = rows - 1;
        cx = Math.min(cx, cols - 1);
        cy = Math.max(0, Math.min(cy, rows - 1));
        wrapPending = false;
        resetTabs();
        version++;
    }

    private Line[] resizeRows(Line[] s, int newRows, boolean isMain) {
        if (newRows == s.length) return s;
        Line[] out = new Line[newRows];
        boolean active = (isMain && !onAlt) || (!isMain && onAlt);
        int drop = 0;
        if (newRows < s.length) {
            // drop blank lines below the cursor first, then scroll the rest into history
            int lastUsed = s.length - 1;
            while (lastUsed > (active ? cy : 0) && s[lastUsed].text().isEmpty()) lastUsed--;
            drop = Math.max(0, lastUsed + 1 - newRows);
            for (int i = 0; i < drop; i++) if (isMain) pushHistory(s[i]);
            if (active) cy -= drop;
        }
        for (int i = 0; i < newRows; i++) {
            int src = i + drop;
            out[i] = src < s.length ? s[src] : new Line(cols, 0);
            out[i].resize(cols);
        }
        return out;
    }

    /**
     * Lines that have scrolled off the top since the start. A line's stable number is scrolled + its row,
     * so a selection stays on the same text while new output arrives.
     */
    long scrolled;

    /** Line by stable number (see {@link #scrolled}), or null if it's gone. */
    Line lineAt(long abs) {
        long row = abs - scrolled;
        if (row < -history.size() || row >= rows) return null;
        return line((int) row);
    }

    private void pushHistory(Line l) {
        scrolled++;
        history.addLast(l);
        while (history.size() > HISTORY) history.removeFirst();
    }

    // ---------------------------------------------------------------- input from the program

    private enum State { GROUND, ESC, ESC_INTER, CSI, OSC, OSC_ESC, STR, STR_ESC }

    private State state = State.GROUND;
    private final int[] params = new int[32];
    private final boolean[] colon = new boolean[32];
    private int nParams;
    private boolean paramStarted;
    private char privateMark, intermediate, escInter;
    private final StringBuilder osc = new StringBuilder();
    private int utf8Need, utf8Cp, utf8Min;

    synchronized void append(byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            int x = b[i] & 0xFF;
            if (utf8Need > 0) {
                if ((x & 0xC0) == 0x80) {
                    utf8Cp = (utf8Cp << 6) | (x & 0x3F);
                    // past U+10FFFF, a surrogate or an overlong form: not a character (Java would throw on it)
                    if (--utf8Need == 0) process(utf8Cp < utf8Min || utf8Cp > 0x10FFFF
                            || (utf8Cp >= 0xD800 && utf8Cp <= 0xDFFF) ? 0xFFFD : utf8Cp);
                    continue;
                }
                utf8Need = 0;
                process(0xFFFD);
            }
            if (x < 0x80) process(x);
            else if ((x & 0xE0) == 0xC0) { utf8Cp = x & 0x1F; utf8Need = 1; utf8Min = 0x80; }
            else if ((x & 0xF0) == 0xE0) { utf8Cp = x & 0x0F; utf8Need = 2; utf8Min = 0x800; }
            else if ((x & 0xF8) == 0xF0) { utf8Cp = x & 0x07; utf8Need = 3; utf8Min = 0x10000; }
            else process(0xFFFD);
        }
        version++;
    }

    private void process(int c) {
        // control characters work in almost every state
        if (c < 0x20 && state != State.OSC && state != State.STR) {
            if (c == 0x1B) {
                if (state == State.OSC_ESC || state == State.STR_ESC) return;
                state = State.ESC;
                escInter = 0;
                return;
            }
            if (c == 0x18 || c == 0x1A) { // CAN, SUB: abort
                state = State.GROUND;
                return;
            }
            control(c);
            return;
        }
        switch (state) {
            case GROUND:
                print(c);
                break;
            case ESC:
                escape(c);
                break;
            case ESC_INTER:
                designate(escInter, c);
                state = State.GROUND;
                break;
            case CSI:
                csiChar(c);
                break;
            case OSC:
                if (c == 0x07) {
                    osc();
                    state = State.GROUND;
                } else if (c == 0x1B) {
                    state = State.OSC_ESC;
                } else if (osc.length() < 4096) {
                    osc.appendCodePoint(c);
                }
                break;
            case OSC_ESC:
                if (c == '\\') osc();
                state = State.GROUND;
                break;
            case STR: // DCS, SOS, PM, APC: ignored until ST
                if (c == 0x1B) state = State.STR_ESC;
                else if (c == 0x07) state = State.GROUND;
                break;
            case STR_ESC:
                state = c == '\\' ? State.GROUND : State.STR;
                break;
        }
    }

    private void control(int c) {
        switch (c) {
            case 0x07: host.onBell(); break;
            case 0x08: // BS
                if (cx > 0) cx--;
                wrapPending = false;
                break;
            case 0x09: tab(1); break;
            case 0x0A: case 0x0B: case 0x0C:
                lineFeed();
                if (newlineMode) cx = 0;
                break;
            case 0x0D: cx = 0; wrapPending = false; break;
            case 0x0E: shifted = true; break;
            case 0x0F: shifted = false; break;
            default: break;
        }
    }

    private void escape(int c) {
        state = State.GROUND;
        switch (c) {
            case '[':
                state = State.CSI;
                nParams = 0;
                paramStarted = false;
                privateMark = 0;
                intermediate = 0;
                Arrays.fill(params, 0);
                Arrays.fill(colon, false);
                break;
            case ']':
                state = State.OSC;
                osc.setLength(0);
                break;
            case 'P': case 'X': case '^': case '_':
                state = State.STR;
                break;
            case '(': case ')': case '*': case '+': case '#': case '%': case ' ':
                escInter = (char) c;
                state = State.ESC_INTER;
                break;
            case '7': saveCursor(); break;
            case '8': restoreCursor(); break;
            case 'D': lineFeed(); break;
            case 'E': cx = 0; lineFeed(); break;
            case 'H': if (cx < cols) tabs[cx] = true; break;
            case 'M': reverseIndex(); break;
            case 'c': reset(); break;
            case '=': appKeypad = true; break;
            case '>': appKeypad = false; break;
            default: break;
        }
    }

    private void designate(char inter, int c) {
        if (inter == '(') g0 = (char) c;
        else if (inter == ')') g1 = (char) c;
        else if (inter == '#' && c == '8') { // DECALN: fill with E
            for (Line l : screen) {
                Arrays.fill(l.cp, 'E');
                Arrays.fill(l.st, 0);
            }
        }
    }

    private void csiChar(int c) {
        if (c >= '0' && c <= '9') {
            if (nParams == 0) nParams = 1;
            int i = nParams - 1;
            if (i < params.length) params[i] = Math.min(params[i] * 10 + (c - '0'), 99999);
            paramStarted = true;
        } else if (c == ';' || c == ':') {
            if (nParams == 0) nParams = 1;
            if (nParams < params.length) {
                colon[nParams] = c == ':';
                nParams++;
            }
        } else if (c >= '<' && c <= '?') {
            privateMark = (char) c;
        } else if (c >= 0x20 && c <= 0x2F) {
            intermediate = (char) c;
        } else if (c >= 0x40 && c <= 0x7E) {
            state = State.GROUND;
            csi((char) c);
        } else {
            state = State.GROUND; // malformed
        }
    }

    private int p(int i, int def) {
        return i < nParams && params[i] != 0 ? params[i] : def;
    }

    private void csi(char f) {
        if (privateMark == '?') {
            if (intermediate == '$' && f == 'p') { decrqm(p(0, 0)); return; }
            if (f == 'h' || f == 'l') {
                for (int i = 0; i < Math.max(1, nParams); i++) decMode(params[i], f == 'h');
                return;
            }
            if (f == 'u') return; // kitty keyboard query: unsupported, no reply
            if (f == 'n' && p(0, 0) == 6) { reply("\033[?" + (cy + 1) + ";" + (cx + 1) + "R"); return; }
            return;
        }
        if (privateMark == '>') {
            if (f == 'c') reply("\033[>1;10;0c");
            else if (f == 'q') reply("\033P>|Buddy\033\\");
            return;
        }
        if (privateMark != 0) return;
        if (intermediate == ' ' && f == 'q') return; // cursor style
        if (intermediate == '!' && f == 'p') { softReset(); return; }
        if (intermediate != 0) return;
        int n = p(0, 1);
        switch (f) {
            case '@': insertChars(n); break;
            case 'A': cy = Math.max(cy < top ? 0 : top, cy - n); wrapPending = false; break;
            case 'B': cy = Math.min(cy > bottom ? rows - 1 : bottom, cy + n); wrapPending = false; break;
            case 'C': case 'a': cx = Math.min(cols - 1, cx + n); wrapPending = false; break;
            case 'D': cx = Math.max(0, cx - n); wrapPending = false; break;
            case 'E': cx = 0; cy = Math.min(bottom, cy + n); wrapPending = false; break;
            case 'F': cx = 0; cy = Math.max(top, cy - n); wrapPending = false; break;
            case 'G': case '`': cx = Math.min(cols - 1, n - 1); wrapPending = false; break;
            case 'H': case 'f': moveTo(p(1, 1) - 1, p(0, 1) - 1); break;
            case 'I': tab(n); break;
            case 'J': eraseDisplay(p(0, 0)); break;
            case 'K': eraseLine(p(0, 0)); break;
            case 'L': insertLines(n); break;
            case 'M': deleteLines(n); break;
            case 'P': deleteChars(n); break;
            case 'S': scrollUp(top, bottom, n); break;
            case 'T': scrollDown(top, bottom, n); break;
            case 'X': screen[cy].clear(cx, cx + n, style); wrapPending = false; break;
            case 'Z': backTab(n); break;
            case 'b': for (int i = 0; i < Math.min(n, 2000); i++) print(lastChar); break;
            case 'c': if (p(0, 0) == 0) reply("\033[?62;22c"); break;
            case 'd': moveTo(cx, n - 1); break;
            case 'e': moveTo(cx, cy + n); break;
            case 'g':
                if (p(0, 0) == 0 && cx < cols) tabs[cx] = false;
                else if (p(0, 0) == 3) Arrays.fill(tabs, false);
                break;
            case 'h': case 'l':
                for (int i = 0; i < Math.max(1, nParams); i++) {
                    if (params[i] == 4) insertMode = f == 'h';
                    else if (params[i] == 20) newlineMode = f == 'h';
                }
                break;
            case 'm': sgr(); break;
            case 'n':
                if (p(0, 0) == 5) reply("\033[0n");
                else if (p(0, 0) == 6) reply("\033[" + (cy + 1) + ";" + (cx + 1) + "R");
                break;
            case 'r': {
                int t = p(0, 1) - 1, b = p(1, rows) - 1;
                if (t < b && b < rows) {
                    top = t;
                    bottom = b;
                } else {
                    top = 0;
                    bottom = rows - 1;
                }
                moveTo(0, 0);
                break;
            }
            case 's': saveCursor(); break;
            case 'u': restoreCursor(); break;
            case 't':
                if (p(0, 0) == 18) reply("\033[8;" + rows + ";" + cols + "t");
                break;
            default: break;
        }
    }

    private void decMode(int mode, boolean on) {
        switch (mode) {
            case 1: appCursor = on; break;
            case 6: originMode = on; moveTo(0, 0); break;
            case 7: autowrap = on; break;
            case 25: cursorVisible = on; break;
            case 47: case 1047: switchScreen(on, false); break;
            case 1048: if (on) saveCursor(); else restoreCursor(); break;
            case 1049: switchScreen(on, true); break;
            case 1000: case 1002: case 1003: mouseTracking = on; break;
            case 2004: bracketedPaste = on; break;
            default: break; // 12 blink, 1004 focus, 1006 SGR mouse, 2026 sync output: accepted, nothing to do
        }
    }

    private void decrqm(int mode) {
        int v;
        switch (mode) {
            case 1: v = appCursor ? 1 : 2; break;
            case 7: v = autowrap ? 1 : 2; break;
            case 25: v = cursorVisible ? 1 : 2; break;
            case 1049: case 47: case 1047: v = onAlt ? 1 : 2; break;
            case 2004: v = bracketedPaste ? 1 : 2; break;
            case 2026: v = 2; break; // known, off (we draw at our own pace anyway)
            default: v = 0;
        }
        reply("\033[?" + mode + ";" + v + "$y");
    }

    private void switchScreen(boolean toAlt, boolean saveCursorToo) {
        if (toAlt == onAlt) return;
        if (toAlt) {
            if (saveCursorToo) saveCursor();
            onAlt = true;
            screen = alt;
            for (Line l : alt) l.clear(0, cols, 0);
        } else {
            onAlt = false;
            screen = main;
            if (saveCursorToo) restoreCursor();
        }
        top = 0;
        bottom = rows - 1;
        wrapPending = false;
    }

    private void sgr() {
        if (nParams == 0) {
            style = 0;
            return;
        }
        for (int i = 0; i < nParams; i++) {
            int a = params[i];
            if (a == 38 || a == 48 || a == 58) {
                int color = -1, used = 0;
                if (i + 1 < nParams && params[i + 1] == 5 && i + 2 < nParams) {
                    color = 1 + (params[i + 2] & 0xFF);
                    used = 2;
                } else if (i + 1 < nParams && params[i + 1] == 2) {
                    // 38;2;r;g;b or 38:2::r:g:b (with an empty colour-space slot)
                    int j = i + 2;
                    if (colon[i + 1] && nParams - j >= 4 && colon[j + 3]) j++;
                    if (j + 2 < nParams) {
                        color = TRUE | ((params[j] & 0xFF) << 16) | ((params[j + 1] & 0xFF) << 8) | (params[j + 2] & 0xFF);
                        used = j + 2 - i;
                    }
                }
                if (color >= 0 && a == 38) style = withFg(style, color);
                else if (color >= 0 && a == 48) style = withBg(style, color);
                i += used;
                continue;
            }
            switch (a) {
                case 0: style = 0; break;
                case 1: style |= BOLD; break;
                case 2: style |= DIM; break;
                case 3: style |= ITALIC; break;
                case 4: style = (i + 1 < nParams && colon[i + 1] && params[i + 1] == 0) ? style & ~UNDERLINE : style | UNDERLINE;
                    if (i + 1 < nParams && colon[i + 1]) i++;
                    break;
                case 5: case 6: style |= BLINK; break;
                case 7: style |= INVERSE; break;
                case 8: style |= INVISIBLE; break;
                case 9: style |= STRIKE; break;
                case 21: style |= UNDERLINE; break;
                case 22: style &= ~(BOLD | DIM); break;
                case 23: style &= ~ITALIC; break;
                case 24: style &= ~UNDERLINE; break;
                case 25: style &= ~BLINK; break;
                case 27: style &= ~INVERSE; break;
                case 28: style &= ~INVISIBLE; break;
                case 29: style &= ~STRIKE; break;
                case 39: style = withFg(style, 0); break;
                case 49: style = withBg(style, 0); break;
                default:
                    if (a >= 30 && a <= 37) style = withFg(style, 1 + a - 30);
                    else if (a >= 40 && a <= 47) style = withBg(style, 1 + a - 40);
                    else if (a >= 90 && a <= 97) style = withFg(style, 1 + 8 + a - 90);
                    else if (a >= 100 && a <= 107) style = withBg(style, 1 + 8 + a - 100);
            }
        }
    }

    private void osc() {
        String s = osc.toString();
        int semi = s.indexOf(';');
        String code = semi < 0 ? s : s.substring(0, semi);
        String arg = semi < 0 ? "" : s.substring(semi + 1);
        switch (code) {
            case "0": case "2":
                title = arg;
                host.onTitle(arg);
                break;
            case "10": case "11":
                if ("?".equals(arg)) {
                    int c = "10".equals(code) ? TermColors.DEFAULT_FG : TermColors.DEFAULT_BG;
                    reply("\033]" + code + ";" + TermColors.xrgb(c) + "\033\\");
                }
                break;
            default: break; // 4 palette, 7 cwd, 8 links, 52 clipboard: ignored
        }
    }

    // ---------------------------------------------------------------- operations

    private void reply(String s) {
        host.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private void print(int c) {
        char cs = shifted ? g1 : g0;
        if (cs == '0' && c >= 0x5F && c <= 0x7E) c = LINE_DRAWING[c - 0x5F];
        int w = TermColors.width(c);
        if (w == 0) return; // combining marks and joiners: not drawn separately
        lastChar = c;
        if (wrapPending) {
            screen[cy].wrapped = true;
            cx = 0;
            lineFeed();
            wrapPending = false;
        }
        if (w == 2 && cx == cols - 1) { // wide char doesn't fit: wrap first
            screen[cy].cp[cx] = 0;
            if (autowrap) {
                screen[cy].wrapped = true;
                cx = 0;
                lineFeed();
            } else {
                return;
            }
        }
        Line l = screen[cy];
        if (insertMode) insertChars(w);
        // writing over half of a wide char clears the other half
        if (l.cp[cx] == WIDE_TAIL && cx > 0) l.cp[cx - 1] = 0;
        if (cx + w < cols && l.cp[cx + w] == WIDE_TAIL) l.cp[cx + w] = 0;
        l.cp[cx] = c;
        l.st[cx] = style;
        if (w == 2) {
            l.cp[cx + 1] = WIDE_TAIL;
            l.st[cx + 1] = style;
        }
        if (cx + w >= cols) {
            if (autowrap) wrapPending = true;
            cx = cols - 1;
        } else {
            cx += w;
        }
    }

    private void lineFeed() {
        wrapPending = false;
        if (cy == bottom) scrollUp(top, bottom, 1);
        else if (cy < rows - 1) cy++;
    }

    private void reverseIndex() {
        wrapPending = false;
        if (cy == top) scrollDown(top, bottom, 1);
        else if (cy > 0) cy--;
    }

    private void scrollUp(int t, int b, int n) {
        n = Math.min(n, b - t + 1);
        for (int k = 0; k < n; k++) {
            Line gone = screen[t];
            if (!onAlt && t == 0) pushHistory(gone);
            System.arraycopy(screen, t + 1, screen, t, b - t);
            screen[b] = new Line(cols, style);
        }
    }

    private void scrollDown(int t, int b, int n) {
        n = Math.min(n, b - t + 1);
        for (int k = 0; k < n; k++) {
            System.arraycopy(screen, t, screen, t + 1, b - t);
            screen[t] = new Line(cols, style);
        }
    }

    private void moveTo(int x, int y) {
        int minY = originMode ? top : 0, maxY = originMode ? bottom : rows - 1;
        cx = Math.max(0, Math.min(cols - 1, x));
        cy = Math.max(minY, Math.min(maxY, y + (originMode ? top : 0)));
        wrapPending = false;
    }

    private void tab(int n) {
        for (int k = 0; k < n && cx < cols - 1; k++) {
            do cx++;
            while (cx < cols - 1 && !tabs[cx]);
        }
        wrapPending = false;
    }

    private void backTab(int n) {
        for (int k = 0; k < n && cx > 0; k++) {
            do cx--;
            while (cx > 0 && !tabs[cx]);
        }
        wrapPending = false;
    }

    private void eraseDisplay(int mode) {
        wrapPending = false;
        if (mode == 0) {
            screen[cy].clear(cx, cols, style);
            for (int y = cy + 1; y < rows; y++) screen[y].clear(0, cols, style);
        } else if (mode == 1) {
            screen[cy].clear(0, cx + 1, style);
            for (int y = 0; y < cy; y++) screen[y].clear(0, cols, style);
        } else if (mode == 2) {
            for (Line l : screen) l.clear(0, cols, style);
        } else if (mode == 3) {
            history.clear();
        }
    }

    private void eraseLine(int mode) {
        wrapPending = false;
        Line l = screen[cy];
        if (mode == 0) l.clear(cx, cols, style);
        else if (mode == 1) l.clear(0, cx + 1, style);
        else l.clear(0, cols, style);
        if (mode != 1) l.wrapped = false;
    }

    private void insertChars(int n) {
        Line l = screen[cy];
        n = Math.min(n, cols - cx);
        System.arraycopy(l.cp, cx, l.cp, cx + n, cols - cx - n);
        System.arraycopy(l.st, cx, l.st, cx + n, cols - cx - n);
        l.clear(cx, cx + n, style);
        wrapPending = false;
    }

    private void deleteChars(int n) {
        Line l = screen[cy];
        n = Math.min(n, cols - cx);
        System.arraycopy(l.cp, cx + n, l.cp, cx, cols - cx - n);
        System.arraycopy(l.st, cx + n, l.st, cx, cols - cx - n);
        l.clear(cols - n, cols, style);
        wrapPending = false;
    }

    private void insertLines(int n) {
        if (cy < top || cy > bottom) return;
        scrollDown(cy, bottom, n);
        cx = 0;
        wrapPending = false;
    }

    private void deleteLines(int n) {
        if (cy < top || cy > bottom) return;
        int saveTop = cy;
        n = Math.min(n, bottom - saveTop + 1);
        for (int k = 0; k < n; k++) {
            System.arraycopy(screen, saveTop + 1, screen, saveTop, bottom - saveTop);
            screen[bottom] = new Line(cols, style);
        }
        cx = 0;
        wrapPending = false;
    }

    private void saveCursor() {
        Saved s = onAlt ? savedAlt : savedMain;
        s.x = cx;
        s.y = cy;
        s.style = style;
        s.origin = originMode;
        s.autowrap = autowrap;
        s.g0 = g0;
        s.g1 = g1;
        s.shifted = shifted;
    }

    private void restoreCursor() {
        Saved s = onAlt ? savedAlt : savedMain;
        cx = Math.min(s.x, cols - 1);
        cy = Math.min(s.y, rows - 1);
        style = s.style;
        originMode = s.origin;
        autowrap = s.autowrap || s.x == 0 && s.y == 0 && s.style == 0;
        g0 = s.g0 == 0 ? 'B' : s.g0;
        g1 = s.g1 == 0 ? 'B' : s.g1;
        shifted = s.shifted;
        wrapPending = false;
    }

    private void softReset() {
        cursorVisible = true;
        originMode = false;
        autowrap = true;
        insertMode = false;
        appCursor = false;
        appKeypad = false;
        top = 0;
        bottom = rows - 1;
        style = 0;
        g0 = g1 = 'B';
        shifted = false;
    }

    private void reset() {
        softReset();
        switchScreen(false, false);
        for (Line l : main) l.clear(0, cols, 0);
        history.clear();
        cx = cy = 0;
        wrapPending = false;
        bracketedPaste = false;
        mouseTracking = false;
        resetTabs();
    }

    /** DEC special graphics (ESC ( 0) for 0x5F..0x7E: line drawing used by dialog, htop, mc… */
    private static final int[] LINE_DRAWING = {
            ' ', '◆', '▒', '␉', '␌', '␍', '␊', '°', '±', '␤', '␋', '┘', '┐', '┌', '└', '┼',
            '⎺', '⎻', '─', '⎼', '⎽', '├', '┤', '┴', '┬', '│', '≤', '≥', 'π', '≠', '£', '·',
    };
}
