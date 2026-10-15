package com.otter.ot;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class CursorProperties {

    /** A sentinel outside the generators' 'a'..'z' alphabet, so it is unique in the marked text. */
    private static final char SENTINEL = '\u0001';

    record CursorCase(String state, Operation op, int anchor, int head, Gravity gravity) {
    }

    record SequenceCursorCase(String state, OperationSequence sequence, int position) {
    }

    @Provide
    Arbitrary<CursorCase> cursorCases() {
        return Generators.applicableOps().flatMap(applicable -> {
            Arbitrary<Integer> position = Arbitraries.integers().between(0, applicable.state().length());
            Arbitrary<Gravity> gravity = Arbitraries.of(Gravity.LEFT, Gravity.RIGHT);
            return Combinators.combine(position, position, gravity)
                    .as((anchor, head, g) -> new CursorCase(applicable.state(), applicable.op(), anchor, head, g));
        });
    }

    @Provide
    Arbitrary<SequenceCursorCase> sequenceCursorCases() {
        return Generators.documents().flatMap(state ->
                Generators.sequenceValidFor(state).flatMap(sequence ->
                        Arbitraries.integers().between(0, state.length())
                                .map(position -> new SequenceCursorCase(state, sequence, position))));
    }

    @Property
    void transformPositionMatchesCharacterAnchorOracle(@ForAll("cursorCases") CursorCase c) {
        assertThat(Ot.transformPosition(c.anchor(), c.op(), c.gravity()))
                .isEqualTo(oracle(c.state(), c.anchor(), c.op(), c.gravity()));
        assertThat(Ot.transformPosition(c.head(), c.op(), c.gravity()))
                .isEqualTo(oracle(c.state(), c.head(), c.op(), c.gravity()));
    }

    @Property
    void transformedPositionStaysInBounds(@ForAll("cursorCases") CursorCase c) {
        int newLength = Ot.apply(c.state(), c.op()).length();
        assertThat(Ot.transformPosition(c.anchor(), c.op(), c.gravity())).isBetween(0, newLength);
        assertThat(Ot.transformPosition(c.head(), c.op(), c.gravity())).isBetween(0, newLength);
    }

    @Property
    void cursorTransformIsComponentwise(@ForAll("cursorCases") CursorCase c) {
        Cursor moved = Ot.transform(new Cursor(c.anchor(), c.head()), c.op(), c.gravity());
        assertThat(moved.anchor()).isEqualTo(Ot.transformPosition(c.anchor(), c.op(), c.gravity()));
        assertThat(moved.head()).isEqualTo(Ot.transformPosition(c.head(), c.op(), c.gravity()));
    }

    @Property
    void sequenceTransformEqualsFoldAndStaysInBounds(@ForAll("sequenceCursorCases") SequenceCursorCase c) {
        int folded = c.position();
        for (Operation op : c.sequence().operations()) {
            folded = Ot.transformPosition(folded, op, Gravity.RIGHT);
        }
        int viaSequence = Ot.transformPosition(c.position(), c.sequence(), Gravity.RIGHT);
        assertThat(viaSequence).isEqualTo(folded);
        assertThat(viaSequence).isBetween(0, Ot.apply(c.state(), c.sequence()).length());
    }

    /**
     * Independent reference: mark the gap with a sentinel character, apply the operation to the
     * marked string by pure string surgery, and read back where the sentinel landed. Shares no
     * branch logic with {@link Ot#transformPosition}, so agreement is real evidence.
     */
    private static int oracle(String state, int position, Operation op, Gravity gravity) {
        String marked = state.substring(0, position) + SENTINEL + state.substring(position);
        String after = switch (op) {
            case NoOp ignored -> marked;
            case Insert insert -> {
                int at = insert.position();
                int index;
                if (at < position) {
                    index = at;
                } else if (at > position) {
                    index = at + 1; // the sentinel occupies slot `position`
                } else {
                    index = gravity == Gravity.RIGHT ? position : position + 1;
                }
                yield marked.substring(0, index) + insert.text() + marked.substring(index);
            }
            case Delete delete -> {
                int start = delete.position();
                int end = start + delete.length();
                StringBuilder builder = new StringBuilder();
                for (int k = 0; k < marked.length(); k++) {
                    char ch = marked.charAt(k);
                    if (ch == SENTINEL) {
                        builder.append(ch);
                        continue;
                    }
                    int original = k < position ? k : k - 1; // sentinel sits at index `position`
                    if (original >= start && original < end) {
                        continue; // this character is deleted
                    }
                    builder.append(ch);
                }
                yield builder.toString();
            }
        };
        return after.indexOf(SENTINEL);
    }
}
