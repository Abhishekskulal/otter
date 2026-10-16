package com.otter.server.persistence;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.otter.ot.Ot;
import com.otter.ot.OperationSequence;
import com.otter.server.ws.protocol.OperationMapper;
import com.otter.server.ws.protocol.WireOp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * R2DBC-backed {@link DocumentStore}. Operation payloads are stored as the same {@link WireOp} JSON
 * that travels on the wire, so a round-trip through the log is identical to a round-trip through a
 * client. Loading is the event-sourcing fold of §7.2: newest snapshot, then every later operation.
 */
@Component
public final class R2dbcDocumentStore implements DocumentStore {

    /** Keep exactly two snapshots per document (§7.2 retention): the newest to load from, the older to serve stale clients. */
    private static final int SNAPSHOTS_RETAINED = 2;

    private final DocumentRepository documents;
    private final OperationRepository operations;
    private final SnapshotRepository snapshots;
    private final ObjectMapper objectMapper;
    /** The declared element type WireOp, so the polymorphic {@code kind} discriminator is written and read. */
    private final JavaType payloadType;

    public R2dbcDocumentStore(DocumentRepository documents, OperationRepository operations,
            SnapshotRepository snapshots, ObjectMapper objectMapper) {
        this.documents = documents;
        this.operations = operations;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.payloadType = objectMapper.getTypeFactory().constructCollectionType(List.class, WireOp.class);
    }

    /** The point the fold starts from: the newest snapshot, or the empty document when none exists yet. */
    private record Base(long revision, String content) {
    }

    @Override
    public Mono<LoadedDocument> load(UUID documentId) {
        return snapshots.latest(documentId)
                .map(snapshot -> new Base(snapshot.revision(), snapshot.content()))
                .defaultIfEmpty(new Base(0L, ""))
                .flatMap(base -> operations.findSince(documentId, base.revision())
                        .collectList()
                        .map(records -> fold(base.revision(), base.content(), records)));
    }

    private LoadedDocument fold(long baseRevision, String baseContent, List<OperationRecord> records) {
        String content = baseContent;
        List<LoadedOperation> loaded = new ArrayList<>(records.size());
        for (OperationRecord record : records) {
            OperationSequence ops = deserialize(record.payload());
            content = Ot.apply(content, ops);
            loaded.add(new LoadedOperation(record.revision(), ops, record.clientOpId(), record.authorId()));
        }
        return new LoadedDocument(baseRevision, content, loaded);
    }

    @Override
    public Mono<Void> ensure(UUID documentId, UUID ownerId) {
        return documents.ensureExists(documentId, ownerId);
    }

    @Override
    public Mono<Void> commit(PersistedOperation operation) {
        return Mono.fromCallable(() -> new OperationRecord(
                        operation.documentId(),
                        operation.revision(),
                        operation.clientOpId(),
                        operation.authorId(),
                        serialize(operation.ops())))
                .flatMap(record -> operations.append(record)
                        .then(documents.updateRevision(operation.documentId(), operation.revision())));
    }

    @Override
    public Mono<Void> snapshot(UUID documentId, long revision, String content) {
        return snapshots.save(new SnapshotRecord(documentId, revision, content))
                .then(snapshots.retentionHorizon(documentId, SNAPSHOTS_RETAINED))
                .flatMap(horizon -> operations.deleteBelow(documentId, horizon)
                        .then(snapshots.pruneKeepingNewest(documentId, SNAPSHOTS_RETAINED)))
                .then();
    }

    private String serialize(OperationSequence ops) {
        try {
            return objectMapper.writerFor(payloadType).writeValueAsString(OperationMapper.toWire(ops));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize operation payload", e);
        }
    }

    private OperationSequence deserialize(String payload) {
        try {
            List<WireOp> wire = objectMapper.readValue(payload, payloadType);
            return OperationMapper.toDomain(wire);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize operation payload", e);
        }
    }
}
