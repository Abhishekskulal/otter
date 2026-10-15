package com.otter.server.persistence;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class DocumentRepository {

    private final DatabaseClient client;

    public DocumentRepository(DatabaseClient client) {
        this.client = client;
    }

    /** Creates the document row on first contact; a no-op once it exists. */
    public Mono<Void> ensureExists(UUID documentId, UUID ownerId) {
        return client.sql("""
                INSERT INTO documents (id, owner_id)
                VALUES (:id, :owner)
                ON CONFLICT (id) DO NOTHING
                """)
                .bind("id", documentId)
                .bind("owner", ownerId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> updateRevision(UUID documentId, long revision) {
        return client.sql("""
                UPDATE documents
                SET current_revision = :revision, updated_at = now()
                WHERE id = :id
                """)
                .bind("id", documentId)
                .bind("revision", revision)
                .fetch()
                .rowsUpdated()
                .then();
    }
}
