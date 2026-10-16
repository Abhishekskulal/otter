package com.otter.server.session;

import com.otter.ot.OperationSequence;
import java.util.UUID;

public record OperationEnvelope(
        UUID clientOpId,
        UUID documentId,
        UUID authorId,
        long baseRevision,
        OperationSequence ops) {
}
