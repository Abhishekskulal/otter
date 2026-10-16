package com.otter.server.session;

import com.otter.ot.Cursor;
import com.otter.ot.Gravity;
import com.otter.ot.InvalidOperationException;
import com.otter.ot.OperationSequence;
import com.otter.ot.Ot;
import com.otter.server.persistence.DocumentStore;
import com.otter.server.persistence.LoadedDocument;
import com.otter.server.persistence.LoadedOperation;
import com.otter.server.persistence.PersistedOperation;
import com.otter.server.ws.protocol.ErrorCode;
import com.otter.server.ws.protocol.OperationMapper;
import com.otter.server.ws.protocol.ServerMessage;
import com.otter.server.ws.protocol.ServerMessage.Participant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Authoritative state for one document, run as an actor. Commands are posted to a single-subscriber
 * {@link #mailbox} and drained by {@code concatMap}, which starts the next command only after the
 * current one has fully completed — including its asynchronous database write. That serialization,
 * not a lock, is what keeps the sequencer and {@link RevisionLog} single-threaded while leaving the
 * event loop free to block on nothing. The durable append happens before the acknowledgement is
 * broadcast (§7.4, synchronous append): no operation is ever acknowledged without being persisted.
 */
public final class DocumentSession {

    /**
     * Producers on different WebSocket threads post to the mailbox concurrently; a unicast sink
     * rejects a non-serialized emission rather than corrupting, so we busy-spin the brief contention.
     */
    private static final Sinks.EmitFailureHandler BUSY_LOOP =
            (signalType, result) -> result == Sinks.EmitResult.FAIL_NON_SERIALIZED;

    private sealed interface Command
            permits Load, Submit, ReadState, Resume, MemberJoined, MemberLeft, CursorMoved {
    }

    private record Load() implements Command {
    }

    private record Submit(OperationEnvelope envelope, Sinks.One<ServerMessage> reply) implements Command {
    }

    private record ReadState(Sinks.One<ServerMessage> reply) implements Command {
    }

    private record Resume(long afterRevision, Sinks.One<List<ServerMessage>> reply) implements Command {
    }

    private record MemberJoined(Participant participant) implements Command {
    }

    private record MemberLeft(UUID userId) implements Command {
    }

    private record CursorMoved(UUID userId, long baseRevision, int anchor, int head) implements Command {
    }

    private final UUID documentId;
    private final DocumentStore store;
    private final int snapshotInterval;

    private final RevisionLog log = new RevisionLog();
    private final Map<UUID, Participant> participants = new LinkedHashMap<>();
    private final Sinks.Many<Command> mailbox = Sinks.many().unicast().onBackpressureBuffer();
    private final Sinks.Many<ServerMessage> broadcast = Sinks.many().multicast().onBackpressureBuffer();
    private final Disposable pump;

    private String content = "";
    private boolean documentEnsured = false;
    private String loadError = null;

    public DocumentSession(UUID documentId, DocumentStore store, int snapshotInterval) {
        this.documentId = documentId;
        this.store = store;
        this.snapshotInterval = snapshotInterval;
        this.pump = mailbox.asFlux().concatMap(this::handle).subscribe();
        enqueue(new Load());
    }

    public UUID documentId() {
        return documentId;
    }

    /** Stream of committed operations. A member subscribes once on join. */
    public Flux<ServerMessage> broadcast() {
        return broadcast.asFlux();
    }

    /** Current state as a sync message, computed on the command loop so it cannot race a commit. */
    public Mono<ServerMessage> currentState() {
        Sinks.One<ServerMessage> reply = Sinks.one();
        enqueue(new ReadState(reply));
        return reply.asMono();
    }

    /**
     * Sequences one operation against the authoritative history and persists it before acknowledging.
     * Resolves to a reply addressed only to the submitter (an error, or the re-sent acknowledgement of
     * an already-seen operation), or completes empty when the acknowledgement travels back through
     * {@link #broadcast}.
     */
    public Mono<ServerMessage> submit(OperationEnvelope envelope) {
        Sinks.One<ServerMessage> reply = Sinks.one();
        enqueue(new Submit(envelope, reply));
        return reply.asMono();
    }

    /**
     * Replays everything committed after {@code afterRevision} for a reconnecting client, ending with
     * a {@code resumed} marker — or a single full {@code sync} when that revision predates retained history.
     */
    public Mono<List<ServerMessage>> resume(long afterRevision) {
        Sinks.One<List<ServerMessage>> reply = Sinks.one();
        enqueue(new Resume(afterRevision, reply));
        return reply.asMono();
    }

    /** Registers a member and announces them to everyone else via a presence frame. */
    public void join(Participant participant) {
        enqueue(new MemberJoined(participant));
    }

    /** Removes a member and announces their departure. */
    public void leave(UUID userId) {
        enqueue(new MemberLeft(userId));
    }

    /**
     * Relays a member's caret: transforms it from {@code baseRevision} up to the current revision
     * using the history already held, then fans it out in the server's revision frame.
     */
    public void moveCursor(UUID userId, long baseRevision, int anchor, int head) {
        enqueue(new CursorMoved(userId, baseRevision, anchor, head));
    }

    public void dispose() {
        broadcast.tryEmitComplete();
        mailbox.tryEmitComplete();
        pump.dispose();
    }

    private void enqueue(Command command) {
        mailbox.emitNext(command, BUSY_LOOP);
    }

    private Mono<Void> handle(Command command) {
        return switch (command) {
            case Load ignored -> store.load(documentId)
                    .doOnNext(this::applyLoad)
                    .onErrorResume(e -> {
                        loadError = "failed to load document: " + e.getMessage();
                        return Mono.empty();
                    })
                    .then();
            case ReadState readState -> {
                readState.reply().tryEmitValue(
                        loadError != null ? error(ErrorCode.PERSISTENCE_FAILURE, loadError) : syncMessage());
                yield Mono.empty();
            }
            case Submit submit -> handleSubmit(submit);
            case Resume resume -> {
                resume.reply().tryEmitValue(buildResume(resume.afterRevision()));
                yield Mono.empty();
            }
            case MemberJoined joined -> {
                participants.put(joined.participant().userId(), joined.participant());
                broadcast.tryEmitNext(new ServerMessage.Presence(
                        documentId, List.of(joined.participant()), List.of()));
                yield Mono.empty();
            }
            case MemberLeft left -> {
                if (participants.remove(left.userId()) != null) {
                    broadcast.tryEmitNext(new ServerMessage.Presence(
                            documentId, List.of(), List.of(left.userId())));
                }
                yield Mono.empty();
            }
            case CursorMoved moved -> {
                handleCursor(moved);
                yield Mono.empty();
            }
        };
    }

    private List<ServerMessage> buildResume(long afterRevision) {
        if (loadError != null) {
            return List.of(error(ErrorCode.PERSISTENCE_FAILURE, loadError));
        }
        // Beyond the retained window (or nonsensical): the client cannot catch up incrementally.
        if (afterRevision < log.oldestRetainedRevision() || afterRevision > log.currentRevision()) {
            return List.of(syncMessage());
        }
        List<ServerMessage> frames = new ArrayList<>();
        for (RevisionLog.Entry entry : log.entriesSince(afterRevision)) {
            frames.add(opMessage(entry));
        }
        frames.add(new ServerMessage.Resumed(documentId, log.currentRevision()));
        return frames;
    }

    private void handleCursor(CursorMoved moved) {
        if (loadError != null) {
            return;
        }
        Cursor cursor = new Cursor(Math.max(0, moved.anchor()), Math.max(0, moved.head()));
        long base = moved.baseRevision();
        if (base >= log.oldestRetainedRevision() && base < log.currentRevision()) {
            for (OperationSequence concurrent : log.since(base)) {
                cursor = Ot.transform(cursor, concurrent, Gravity.RIGHT);
            }
        }
        int limit = content.length();
        broadcast.tryEmitNext(new ServerMessage.Cursor(
                documentId,
                moved.userId(),
                log.currentRevision(),
                Math.min(cursor.anchor(), limit),
                Math.min(cursor.head(), limit)));
    }

    private void applyLoad(LoadedDocument document) {
        List<RevisionLog.Entry> entries = new ArrayList<>(document.operations().size());
        for (LoadedOperation op : document.operations()) {
            entries.add(new RevisionLog.Entry(op.revision(), op.ops(), op.clientOpId(), op.authorId()));
        }
        log.seed(document.baseRevision(), entries);
        content = document.content();
        // Anything persisted means the documents row already exists, so later commits can skip ensure.
        documentEnsured = document.baseRevision() > 0 || !entries.isEmpty();
    }

    private Mono<Void> handleSubmit(Submit submit) {
        Sinks.One<ServerMessage> reply = submit.reply();
        OperationEnvelope envelope = submit.envelope();

        if (loadError != null) {
            reply.tryEmitValue(error(ErrorCode.PERSISTENCE_FAILURE, loadError));
            return Mono.empty();
        }
        if (envelope.baseRevision() > log.currentRevision()) {
            reply.tryEmitValue(error(ErrorCode.REVISION_FROM_FUTURE,
                    "baseRevision " + envelope.baseRevision() + " exceeds " + log.currentRevision()));
            return Mono.empty();
        }
        if (envelope.baseRevision() < log.oldestRetainedRevision()) {
            reply.tryEmitValue(error(ErrorCode.REVISION_TOO_OLD,
                    "baseRevision " + envelope.baseRevision() + " predates retained history"));
            return Mono.empty();
        }

        RevisionLog.Entry seen = log.findByClientOpId(envelope.clientOpId());
        if (seen != null) {
            reply.tryEmitValue(opMessage(seen));
            return Mono.empty();
        }

        OperationSequence transformed = envelope.ops();
        for (OperationSequence concurrent : log.since(envelope.baseRevision())) {
            transformed = Ot.transform(transformed, concurrent).aPrime();
        }

        final String nextContent;
        try {
            nextContent = Ot.apply(content, transformed);
        } catch (InvalidOperationException e) {
            reply.tryEmitValue(error(ErrorCode.INVALID_OPERATION, e.getMessage()));
            return Mono.empty();
        }

        final OperationSequence committedOps = transformed;
        final long revision = log.currentRevision() + 1;
        PersistedOperation record = new PersistedOperation(
                documentId, revision, envelope.clientOpId(), envelope.authorId(), committedOps);

        Mono<Void> persist = documentEnsured
                ? store.commit(record)
                : store.ensure(documentId, envelope.authorId()).then(store.commit(record));

        return persist
                .then(Mono.fromRunnable(() -> {
                    documentEnsured = true;
                    content = nextContent;
                    RevisionLog.Entry committed =
                            log.append(committedOps, envelope.clientOpId(), envelope.authorId());
                    broadcast.tryEmitNext(opMessage(committed));
                    reply.tryEmitEmpty();
                }))
                .then(maybeSnapshot(revision, nextContent))
                .onErrorResume(e -> {
                    reply.tryEmitValue(error(ErrorCode.PERSISTENCE_FAILURE,
                            "failed to persist operation: " + e.getMessage()));
                    return Mono.empty();
                });
    }

    private Mono<Void> maybeSnapshot(long revision, String contentAtRevision) {
        if (snapshotInterval <= 0 || revision % snapshotInterval != 0) {
            return Mono.empty();
        }
        // A snapshot is an optimisation over replay; its failure must never unacknowledge a durable op.
        return store.snapshot(documentId, revision, contentAtRevision).onErrorResume(e -> Mono.empty());
    }

    private ServerMessage syncMessage() {
        return new ServerMessage.Sync(
                documentId, log.currentRevision(), content, List.copyOf(participants.values()));
    }

    private ServerMessage.Op opMessage(RevisionLog.Entry entry) {
        return new ServerMessage.Op(
                documentId,
                entry.revision(),
                entry.clientOpId(),
                entry.authorId(),
                OperationMapper.toWire(entry.ops()));
    }

    private ServerMessage.Error error(ErrorCode code, String message) {
        return new ServerMessage.Error(documentId, code.name(), message);
    }
}
