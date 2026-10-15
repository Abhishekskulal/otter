package com.otter.server.session;

import com.otter.server.persistence.DocumentStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Maps a document id to its live {@link DocumentSession}, creating one on first join and evicting it
 * once the last member leaves. Eviction is safe now that state is durable: a new session rebuilds
 * itself from the log on its first command, so discarding the in-memory copy loses nothing.
 */
@Component
public final class DocumentSessionRegistry {

    private static final class Entry {
        final DocumentSession session;
        int members;

        Entry(DocumentSession session) {
            this.session = session;
        }
    }

    private final DocumentStore store;
    private final int snapshotInterval;
    private final ConcurrentHashMap<UUID, Entry> sessions = new ConcurrentHashMap<>();

    public DocumentSessionRegistry(DocumentStore store,
            @Value("${otter.snapshot-interval:500}") int snapshotInterval) {
        this.store = store;
        this.snapshotInterval = snapshotInterval;
    }

    public DocumentSession join(UUID documentId) {
        return sessions.compute(documentId, (id, existing) -> {
            Entry entry = existing != null
                    ? existing
                    : new Entry(new DocumentSession(id, store, snapshotInterval));
            entry.members++;
            return entry;
        }).session;
    }

    public void leave(UUID documentId) {
        sessions.computeIfPresent(documentId, (id, entry) -> {
            if (--entry.members > 0) {
                return entry;
            }
            entry.session.dispose();
            return null;
        });
    }

    public int activeSessions() {
        return sessions.size();
    }
}
