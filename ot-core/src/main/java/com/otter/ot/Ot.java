package com.otter.ot;

import java.util.ArrayList;
import java.util.List;

public final class Ot {

    private Ot() {
    }

    public static String apply(String state, Operation op) {
        return switch (op) {
            case NoOp ignored -> state;
            case Insert insert -> {
                if (insert.position() > state.length()) {
                    throw new InvalidOperationException(
                            "Insert out of bounds: " + insert + " for state length " + state.length());
                }
                yield state.substring(0, insert.position())
                        + insert.text()
                        + state.substring(insert.position());
            }
            case Delete delete -> {
                if (delete.position() > state.length()
                        || delete.length() > state.length() - delete.position()) {
                    throw new InvalidOperationException(
                            "Delete out of bounds: " + delete + " for state length " + state.length());
                }
                yield state.substring(0, delete.position())
                        + state.substring(delete.position() + delete.length());
            }
        };
    }

    public static TransformResult transform(Operation a, Operation b) {
        return switch (a) {
            case NoOp() -> new TransformResult(a, b);
            case Insert ai -> switch (b) {
                case NoOp() -> new TransformResult(a, b);
                case Insert bi -> insertInsert(ai, bi);
                case Delete bd -> insertDelete(ai, bd);
            };
            case Delete ad -> switch (b) {
                case NoOp() -> new TransformResult(a, b);
                case Insert bi -> deleteInsert(ad, bi);
                case Delete bd -> deleteDelete(ad, bd);
            };
        };
    }

    private static TransformResult insertInsert(Insert a, Insert b) {
        if (a.position() <= b.position()) {
            return new TransformResult(
                    a,
                    new Insert(b.position() + a.text().length(), b.text()));
        }
        return new TransformResult(
                new Insert(a.position() + b.text().length(), a.text()),
                b);
    }

    private static TransformResult insertDelete(Insert a, Delete b) {
        int insertPos = a.position();
        int deleteStart = b.position();
        int deleteEnd = b.position() + b.length();
        int insertLen = a.text().length();

        if (insertPos <= deleteStart) {
            return new TransformResult(a, new Delete(deleteStart + insertLen, b.length()));
        }
        if (insertPos >= deleteEnd) {
            return new TransformResult(new Insert(insertPos - b.length(), a.text()), b);
        }
        return new TransformResult(new NoOp(), new Delete(deleteStart, b.length() + insertLen));
    }

    private static TransformResult deleteInsert(Delete a, Insert b) {
        int deleteStart = a.position();
        int deleteEnd = a.position() + a.length();
        int insertPos = b.position();
        int insertLen = b.text().length();

        if (insertPos <= deleteStart) {
            return new TransformResult(new Delete(deleteStart + insertLen, a.length()), b);
        }
        if (insertPos >= deleteEnd) {
            return new TransformResult(a, new Insert(insertPos - a.length(), b.text()));
        }
        return new TransformResult(new Delete(deleteStart, a.length() + insertLen), new NoOp());
    }

    private static TransformResult deleteDelete(Delete a, Delete b) {
        int aStart = a.position();
        int aEnd = a.position() + a.length();
        int bStart = b.position();
        int bEnd = b.position() + b.length();

        int overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));

        int aLength = a.length() - overlap;
        int bLength = b.length() - overlap;

        int aShift = Math.max(0, Math.min(bEnd, aStart) - bStart);
        int bShift = Math.max(0, Math.min(aEnd, bStart) - aStart);

        Operation aPrime = aLength == 0 ? new NoOp() : new Delete(aStart - aShift, aLength);
        Operation bPrime = bLength == 0 ? new NoOp() : new Delete(bStart - bShift, bLength);

        return new TransformResult(aPrime, bPrime);
    }

    public static SequenceTransformResult transform(OperationSequence a, OperationSequence b) {
        List<Operation> aPrime = new ArrayList<>();
        OperationSequence remainingB = b;
        for (Operation op : a.operations()) {
            Operation opPrime = op;
            List<Operation> bNext = new ArrayList<>();
            for (Operation bOp : remainingB.operations()) {
                TransformResult step = transform(opPrime, bOp);
                opPrime = step.aPrime();
                bNext.add(step.bPrime());
            }
            aPrime.add(opPrime);
            remainingB = new OperationSequence(bNext);
        }
        return new SequenceTransformResult(new OperationSequence(aPrime), remainingB);
    }

    public static String apply(String state, OperationSequence sequence) {
        String result = state;
        for (Operation op : sequence.operations()) {
            result = apply(result, op);
        }
        return result;
    }

    public static OperationSequence compose(Operation first, Operation second) {
        return normalize(List.of(first, second));
    }

    public static OperationSequence compose(OperationSequence sequence, Operation next) {
        List<Operation> ops = new ArrayList<>(sequence.operations());
        ops.add(next);
        return normalize(ops);
    }

    /**
     * Shifts a gap position so it points at the same place in the document after {@code op} is
     * applied. An insert before the position moves it right by the inserted length; a delete before
     * it moves it left by the removed length; a delete straddling it collapses it to the cut point.
     * {@code gravity} decides only the case where an insert lands exactly on the position.
     */
    public static int transformPosition(int position, Operation op, Gravity gravity) {
        return switch (op) {
            case NoOp ignored -> position;
            case Insert insert -> {
                int at = insert.position();
                int length = insert.text().length();
                if (at < position) {
                    yield position + length;
                }
                if (at > position) {
                    yield position;
                }
                yield gravity == Gravity.RIGHT ? position + length : position;
            }
            case Delete delete -> {
                int start = delete.position();
                int end = start + delete.length();
                if (position <= start) {
                    yield position;
                }
                if (position >= end) {
                    yield position - delete.length();
                }
                yield start;
            }
        };
    }

    /** Shifts a position forward through every operation of {@code sequence}, in order. */
    public static int transformPosition(int position, OperationSequence sequence, Gravity gravity) {
        int result = position;
        for (Operation op : sequence.operations()) {
            result = transformPosition(result, op, gravity);
        }
        return result;
    }

    public static Cursor transform(Cursor cursor, Operation op, Gravity gravity) {
        return new Cursor(
                transformPosition(cursor.anchor(), op, gravity),
                transformPosition(cursor.head(), op, gravity));
    }

    public static Cursor transform(Cursor cursor, OperationSequence sequence, Gravity gravity) {
        return new Cursor(
                transformPosition(cursor.anchor(), sequence, gravity),
                transformPosition(cursor.head(), sequence, gravity));
    }

    private static OperationSequence normalize(List<Operation> ops) {
        List<Operation> out = new ArrayList<>();
        for (Operation op : ops) {
            if (op instanceof NoOp) {
                continue;
            }
            out.add(op);
        }
        return new OperationSequence(out);
    }
}
