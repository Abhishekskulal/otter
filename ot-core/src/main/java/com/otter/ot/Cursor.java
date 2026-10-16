package com.otter.ot;

/**
 * A selection as two independent positions. {@code anchor} is where the selection began and
 * {@code head} is where the caret is; they are equal for a plain caret with no selection. Each is a
 * gap index into the text, transformed independently when the document changes underneath it.
 */
public record Cursor(int anchor, int head) {

    public Cursor {
        if (anchor < 0) {
            throw new IllegalArgumentException("Cursor anchor must be >= 0, got " + anchor);
        }
        if (head < 0) {
            throw new IllegalArgumentException("Cursor head must be >= 0, got " + head);
        }
    }
}
