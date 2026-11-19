package ai.tradingassist.bookmap;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;

/** Retains only the newest log text, including when a single append exceeds the cap. */
final class BoundedLogDocument extends PlainDocument {
    static final int MAX_CHARACTERS = 128 * 1024;

    @Override public void insertString(int offset, String text, AttributeSet attributes)
            throws BadLocationException {
        if (text == null) return;
        if (text.length() > MAX_CHARACTERS) text = text.substring(text.length() - MAX_CHARACTERS);
        int excess = Math.max(0, getLength() + text.length() - MAX_CHARACTERS);
        if (excess > 0) {
            remove(0, excess);
            offset = Math.max(0, offset - excess);
        }
        super.insertString(offset, text, attributes);
    }
}
