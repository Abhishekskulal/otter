package com.otter.server.persistence;

import java.util.UUID;

public record OperationRecord(
        UUID documentId,
        long revision,
        UUID clientOpId,
        UUID authorId,
        String payload) {
}
