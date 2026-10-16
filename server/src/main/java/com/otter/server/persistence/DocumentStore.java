package com.otter.server.persistence;

import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Durable storage for a document's event-sourced history, as the session sees it. Keeps the
 * reactive sequencer independent of R2DBC: the session depends on this port, not on a concrete
 * repository, which is what lets the convergence tests run without a database.
 */
public interface DocumentStore {

    /** Rebuilds state for a document: the newest snapshot folded with every operation committed since. */
    Mono<LoadedDocument> load(UUID documentId);

    /** Creates the {@code documents} row on first contact. Idempotent; a no-op once it exists. */
    Mono<Void> ensure(UUID documentId, UUID ownerId);

    /** Appends one committed operation and advances the document's current revision, in that order. */
    Mono<Void> commit(PersistedOperation operation);

    /** Captures a point-in-time snapshot and compacts operations the retained snapshots no longer need. */
    Mono<Void> snapshot(UUID documentId, long revision, String content);
}
