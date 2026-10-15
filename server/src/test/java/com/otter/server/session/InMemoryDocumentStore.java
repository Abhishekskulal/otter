package com.otter.server.session;

import com.otter.server.persistence.DocumentStore;
import com.otter.server.persistence.LoadedDocument;
import com.otter.server.persistence.PersistedOperation;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * A {@link DocumentStore} that persists nothing, for tests that exercise the sequencer in isolation.
 * {@code load} always returns the empty document, so each session starts from revision 0.
 */
final class InMemoryDocumentStore implements DocumentStore {

    @Override
    public Mono<LoadedDocument> load(UUID documentId) {
        return Mono.just(new LoadedDocument(0L, "", List.of()));
    }

    @Override
    public Mono<Void> ensure(UUID documentId, UUID ownerId) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> commit(PersistedOperation operation) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> snapshot(UUID documentId, long revision, String content) {
        return Mono.empty();
    }
}
