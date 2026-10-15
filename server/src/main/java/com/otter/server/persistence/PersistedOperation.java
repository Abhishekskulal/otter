package com.otter.server.persistence;

import com.otter.ot.OperationSequence;
import java.util.UUID;

/**
 * A sequenced operation handed to the store for durable append. {@code ops} is the TRANSFORMED
 * sequence as applied to server state (§7.2) — self-contained, so replay reproduces the exact state.
 */
public record PersistedOperation(
        UUID documentId,
        long revision,
        UUID clientOpId,
        UUID authorId,
        OperationSequence ops) {
}
