package com.otter.server.persistence;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class SnapshotRepository {

    private final DatabaseClient client;

    public SnapshotRepository(DatabaseClient client) {
        this.client = client;
    }

    public Mono<Void> save(SnapshotRecord record) {
        return client.sql("""
                INSERT INTO document_snapshots (document_id, revision, content)
                VALUES (:documentId, :revision, :content)
                ON CONFLICT (document_id, revision) DO NOTHING
                """)
                .bind("documentId", record.documentId())
                .bind("revision", record.revision())
                .bind("content", record.content())
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<SnapshotRecord> latest(UUID documentId) {
        return client.sql("""
                SELECT document_id, revision, content
                FROM document_snapshots
                WHERE document_id = :documentId
                ORDER BY revision DESC
                LIMIT 1
                """)
                .bind("documentId", documentId)
                .map((row, meta) -> new SnapshotRecord(
                        row.get("document_id", UUID.class),
                        row.get("revision", Long.class),
                        row.get("content", String.class)))
                .one();
    }

    /**
     * Revision of the oldest snapshot to retain. Operations below it can be compacted. Returns 0
     * when fewer than {@code keep} snapshots exist, so nothing is pruned yet.
     */
    public Mono<Long> retentionHorizon(UUID documentId, int keep) {
        return client.sql("""
                SELECT COALESCE(MIN(revision), 0) AS horizon
                FROM (
                    SELECT revision
                    FROM document_snapshots
                    WHERE document_id = :documentId
                    ORDER BY revision DESC
                    LIMIT :keep
                ) retained
                """)
                .bind("documentId", documentId)
                .bind("keep", keep)
                .map((row, meta) -> row.get("horizon", Long.class))
                .one();
    }

    public Mono<Long> pruneKeepingNewest(UUID documentId, int keep) {
        return client.sql("""
                DELETE FROM document_snapshots
                WHERE document_id = :documentId
                  AND revision < (
                    SELECT COALESCE(MIN(revision), 0)
                    FROM (
                        SELECT revision FROM document_snapshots
                        WHERE document_id = :documentId
                        ORDER BY revision DESC
                        LIMIT :keep
                    ) retained
                  )
                """)
                .bind("documentId", documentId)
                .bind("keep", keep)
                .fetch()
                .rowsUpdated();
    }
}
