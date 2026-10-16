package com.otter.ot;

import java.util.List;

public record OperationSequence(List<Operation> operations) {

    public OperationSequence {
        operations = List.copyOf(operations);
    }

    public static OperationSequence empty() {
        return new OperationSequence(List.of());
    }
}
