package com.otter.ot;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

public final class Generators {

    private Generators() {
    }

    public record ApplicableOp(String state, Operation op) {
    }

    public record ConcurrentOps(String state, Operation a, Operation b) {
    }

    public record SequentialOps(String state, Operation a, Operation b) {
    }

    public record ConcurrentSequences(String state, OperationSequence a, OperationSequence b) {
    }

    public static Arbitrary<String> documents() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(20);
    }

    public static Arbitrary<String> insertText() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(5);
    }

    public static Arbitrary<Operation> operationValidFor(String state) {
        Arbitrary<Operation> noop = Arbitraries.<Operation>just(new NoOp());
        Arbitrary<Operation> insert = Combinators
                .combine(Arbitraries.integers().between(0, state.length()), insertText())
                .as((position, text) -> (Operation) new Insert(position, text));

        if (state.isEmpty()) {
            return Arbitraries.oneOf(noop, insert);
        }

        Arbitrary<Operation> delete = Arbitraries.integers().between(0, state.length() - 1)
                .flatMap(position -> Arbitraries.integers().between(1, state.length() - position)
                        .map(length -> (Operation) new Delete(position, length)));

        return Arbitraries.oneOf(noop, insert, delete);
    }

    public static Arbitrary<ApplicableOp> applicableOps() {
        return documents().flatMap(state ->
                operationValidFor(state).map(op -> new ApplicableOp(state, op)));
    }

    public static Arbitrary<ConcurrentOps> concurrentOps() {
        return documents().flatMap(state ->
                Combinators.combine(operationValidFor(state), operationValidFor(state))
                        .as((a, b) -> new ConcurrentOps(state, a, b)));
    }

    public static Arbitrary<SequentialOps> sequentialOps() {
        return documents().flatMap(state ->
                operationValidFor(state).flatMap(a -> {
                    String afterA = Ot.apply(state, a);
                    return operationValidFor(afterA).map(b -> new SequentialOps(state, a, b));
                }));
    }

    private record RawOp(int kind, int positionSeed, String text, int lengthSeed) {
    }

    private static Arbitrary<RawOp> rawOps() {
        return Combinators.combine(
                Arbitraries.integers().between(0, 2),
                Arbitraries.integers().between(0, 1000),
                insertText(),
                Arbitraries.integers().between(0, 1000)).as(RawOp::new);
    }

    public static Arbitrary<OperationSequence> sequenceValidFor(String state) {
        return rawOps().list().ofMaxSize(4).map(raws -> foldRaw(state, raws));
    }

    private static OperationSequence foldRaw(String state, List<RawOp> raws) {
        List<Operation> ops = new ArrayList<>();
        String current = state;
        for (RawOp raw : raws) {
            Operation op = concretize(current, raw);
            ops.add(op);
            current = Ot.apply(current, op);
        }
        return new OperationSequence(ops);
    }

    private static Operation concretize(String state, RawOp raw) {
        int length = state.length();
        if (raw.kind() == 0) {
            return new NoOp();
        }
        if (raw.kind() == 1) {
            return new Insert(raw.positionSeed() % (length + 1), raw.text());
        }
        if (length == 0) {
            return new NoOp();
        }
        int position = raw.positionSeed() % length;
        int deleteLength = 1 + raw.lengthSeed() % (length - position);
        return new Delete(position, deleteLength);
    }

    public static Arbitrary<ConcurrentSequences> concurrentSequences() {
        return documents().flatMap(state ->
                Combinators.combine(sequenceValidFor(state), sequenceValidFor(state))
                        .as((a, b) -> new ConcurrentSequences(state, a, b)));
    }
}
