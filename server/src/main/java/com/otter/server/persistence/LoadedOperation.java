package com.otter.server.persistence;

import com.otter.ot.OperationSequence;
import java.util.UUID;

/** One committed operation read back from the log, carrying everything needed to rehydrate a log entry. */
public record LoadedOperation(long revision, OperationSequence ops, UUID clientOpId, UUID authorId) {
}
