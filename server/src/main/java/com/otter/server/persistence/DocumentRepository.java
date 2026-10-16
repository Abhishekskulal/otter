package com.otter.server.persistence;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class DocumentRepository {

    private final DatabaseClient client;

    public DocumentRepository(DatabaseClient client) {
        this.client = client;
    }

    private static final String SUMMARY_COLUMNS = "id, title, current_revision, updated_at";

    /** Creates a titled document. Fails if the id already exists — callers generate a fresh id. */
    public Mono<Void> create(UUID id, String title, UUID ownerId) {
        return client.sql("INSERT INTO documents (id, title, owner_id) VALUES (:id, :title, :owner)")
                .bind("id", id)
                .bind("title", title)
                .bind("owner", ownerId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Flux<DocumentSummary> list() {
        return client.sql("SELECT " + SUMMARY_COLUMNS + " FROM documents ORDER BY updated_at DESC")
                .map(DocumentRepository::toSummary)
                .all();
    }

    public Mono<DocumentSummary> findById(UUID id) {
        return client.sql("SELECT " + SUMMARY_COLUMNS + " FROM documents WHERE id = :id")
                .bind("id", id)
                .map(DocumentRepository::toSummary)
                .one();
    }

    /** Renames a document; the returned count is 0 when no such document exists. */
    public Mono<Long> rename(UUID id, String title) {
        return client.sql("UPDATE documents SET title = :title, updated_at = now() WHERE id = :id")
                .bind("id", id)
                .bind("title", title)
                .fetch()
                .rowsUpdated();
    }

    private static DocumentSummary toSummary(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
        return new DocumentSummary(
                row.get("id", UUID.class),
                row.get("title", String.class),
                row.get("current_revision", Long.class),
                row.get("updated_at", java.time.OffsetDateTime.class));
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
