package com.otter.ot;

/**
 * How a position behaves when an insertion lands exactly on it. {@code RIGHT} lets the inserted text
 * push the position along (the position stays to the right of the new text); {@code LEFT} keeps the
 * position fixed, so the text appears to its right. This is the only ambiguous case in position
 * transformation — every other case is forced by the arithmetic.
 */
public enum Gravity {
    LEFT,
    RIGHT
}
