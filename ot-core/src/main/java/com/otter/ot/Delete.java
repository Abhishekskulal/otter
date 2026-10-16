package com.otter.ot;

public record Delete(int position, int length) implements Operation {

    public Delete {
        if (position < 0) {
            throw new IllegalArgumentException("Delete position must be >= 0, got " + position);
        }
        if (length < 1) {
            throw new IllegalArgumentException("Delete length must be >= 1, got " + length);
        }
    }
}
