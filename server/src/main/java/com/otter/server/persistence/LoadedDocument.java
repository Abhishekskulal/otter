package com.otter.server.persistence;

import java.util.List;

/**
 * A document reconstructed from durable storage. {@code baseRevision} is the revision captured by
 * the snapshot the fold started from (0 when none exists); {@code operations} are the committed
 * operations after it, in revision order, so the live revision is {@code baseRevision + operations.size()}.
 */
public record LoadedDocument(long baseRevision, String content, List<LoadedOperation> operations) {
}
