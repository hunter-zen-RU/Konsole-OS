package com.termux.terminal;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TerminalSessionInterceptorTest extends TestCase {

    private static class StubClient implements TerminalSessionClient {
        int textChanges = 0;

        @Override public void onTextChanged(TerminalSession changedSession) { textChanges++; }
        @Override public void onTitleChanged(TerminalSession changedSession) {}
        @Override public void onSessionFinished(TerminalSession finishedSession) {}
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
        @Override public Integer getTerminalCursorStyle() { return 0; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }

    @Override
    protected void tearDown() throws Exception {
        TerminalSession.setInputInterceptor(null);
        super.tearDown();
    }

    private TerminalSession createTestSession() throws Exception {
        StubClient client = new StubClient();
        TerminalSession session = new TerminalSession("/system/bin/sh", "/", new String[]{"sh"}, new String[]{}, 100, client);
        TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 10, 20, 100, client);
        Field emulatorField = TerminalSession.class.getDeclaredField("mEmulator");
        emulatorField.setAccessible(true);
        emulatorField.set(session, emulator);
        return session;
    }

    private void send(TerminalSession session, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);
    }

    public void testUtf8TypingAutocompleteHistoryAndCtrlC() throws Exception {
        TerminalSession session = createTestSession();
        List<String> dispatched = new ArrayList<>();
        List<String> history = Arrays.asList("kons sys", "echo café");
        final boolean[] active = new boolean[]{true};

        TerminalSession.setInputInterceptor(new TerminalSession.KonsoleInputInterceptor() {
            @Override
            public boolean onInterceptCommand(TerminalSession s, String command) {
                dispatched.add(command);
                return true;
            }

            @Override
            public String getPrompt(TerminalSession s) {
                return "$ ";
            }

            @Override
            public int getCommandStatus(String firstWord) {
                return 1;
            }

            @Override
            public String predictSuggestion(String currentInput) {
                if ("kon".equals(currentInput)) return "s sys";
                if ("typo".equals(currentInput)) return " (did you mean: top?)";
                return null;
            }

            @Override
            public boolean isInterceptionActive(TerminalSession s) {
                return active[0];
            }

            @Override
            public String getHistoryEntry(int index) {
                if (index >= 0 && index < history.size()) return history.get(index);
                return null;
            }
        });

        // 1. Right-arrow autocomplete completes "kon" -> "kons sys"
        send(session, "kon");
        send(session, "\033[C");
        send(session, "\r");
        assertEquals(1, dispatched.size());
        assertEquals("kons sys", dispatched.get(0));

        // 2. Parenthetical typo hints must NOT be accepted on Tab or Right-arrow
        send(session, "typo");
        send(session, "\033[C");
        send(session, "\t");
        send(session, "\r");
        assertEquals(2, dispatched.size());
        assertEquals("typo", dispatched.get(1));

        // 3. History navigation Up -> Down -> Down (back to empty) + UTF-8 preservation
        send(session, "\033[A"); // "kons sys"
        send(session, "\033[A"); // "echo café"
        send(session, "\r");
        assertEquals(3, dispatched.size());
        assertEquals("echo café", dispatched.get(2));

        // 4. Ctrl+C clears current line buffer
        send(session, "partial");
        session.write(new byte[]{3}, 0, 1);
        send(session, "after\r");
        assertEquals(4, dispatched.size());
        assertEquals("after", dispatched.get(3));

        // 5. Disabling interceptor skips command interception
        active[0] = false;
        send(session, "ignored\r");
        assertEquals(4, dispatched.size());
    }
}
