package lanmonitoringserver.util;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

/** Bounded append helper for long-running Swing logs. Must run on the EDT. */
public final class SwingTextLimiter {
    public static final int DEFAULT_MAX_CHARACTERS = 200_000;
    private SwingTextLimiter() {}

    public static void append(JTextArea area, String text) {
        append(area, text, DEFAULT_MAX_CHARACTERS);
    }

    public static void append(JTextArea area, String text, int maximumCharacters) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Swing text must be updated on the EDT");
        }
        if (maximumCharacters < 1) throw new IllegalArgumentException("maximumCharacters must be positive");
        area.append(text == null ? "" : text);
        int excess = area.getDocument().getLength() - maximumCharacters;
        if (excess <= 0) return;
        try { area.getDocument().remove(0, excess); }
        catch (BadLocationException impossible) { throw new IllegalStateException(impossible); }
    }
}
