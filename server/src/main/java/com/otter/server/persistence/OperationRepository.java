package com.otter.server.persistence;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public class OperationRepository {

    private final DatabaseClient client;

    public OperationRepository(DatabaseClient client) {
        this.client = client;
    }

    public Mono<Void> append(OperationRecord record) {
        return client.sql("""
                INSERT INTO operations (document_id, revision, client_op_id, author_id, payload)
                VALUES (:documentId, :revision, :clientOpId, :authorId, :payload::jsonb)
                """)
                .bind("documentId", record.documentId())
                .bind("revision", record.revision())
                .bind("clientOpId", record.clientOpId())
                .bind("authorId", record.authorId())
                .bind("payload", record.payload())
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Flux<OperationRecord> findSince(UUID documentId, long revision) {
        return client.sql("""
                SELECT document_id, revision, client_op_id, author_id, payload::text AS payload
                FROM operations
                WHERE document_id = :documentId AND revision > :revision
                ORDER BY revision
                """)
                .bind("documentId", documentId)
                .bind("revision", revision)
                .map((row, meta) -> new OperationRecord(
                        row.get("document_id", UUID.class),
                        row.get("revision", Long.class),
                        row.get("client_op_id", UUID.class),
                        row.get("author_id", UUID.class),
                        row.get("payload", String.class)))
                .all();
    }

    public Mono<Long> maxRevision(UUID documentId) {
        return client.sql("SELECT COALESCE(MAX(revision), 0) AS max FROM operations WHERE document_id = :documentId")
                .bind("documentId", documentId)
                .map((row, meta) -> row.get("max", Long.class))
                .one();
    }

    /** Retention compaction: drop operations the oldest retained snapshot no longer needs. */
    public Mono<Long> deleteBelow(UUID documentId, long revision) {
        return client.sql("DELETE FROM operations WHERE document_id = :documentId AND revision < :revision")
                .bind("documentId", documentId)
                .bind("revision", revision)
                .fetch()
                .rowsUpdated();
    }
}
