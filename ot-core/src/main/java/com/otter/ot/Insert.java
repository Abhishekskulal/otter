package com.otter.ot;

public record Insert(int position, String text) implements Operation {

    public Insert {
        if (position < 0) {
            throw new IllegalArgumentException("Insert position must be >= 0, got " + position);
        }
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Insert text must be non-empty");
        }
    }
}
