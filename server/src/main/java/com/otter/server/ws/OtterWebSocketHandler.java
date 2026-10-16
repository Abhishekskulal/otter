package com.otter.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.otter.server.persistence.UserRecord;
import com.otter.server.persistence.UserRepository;
import com.otter.server.session.DocumentSession;
import com.otter.server.session.DocumentSessionRegistry;
import com.otter.server.session.OperationEnvelope;
import com.otter.server.ws.protocol.ClientMessage;
import com.otter.server.ws.protocol.OperationMapper;
import com.otter.server.ws.protocol.ServerMessage;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
public final class OtterWebSocketHandler implements WebSocketHandler {

    private record Membership(DocumentSession session, Disposable subscription) {
    }

    /** Per-connection identity; adopted from the client's join/resume so it is stable across reconnects. */
    private static final class Connection {
        private volatile ServerMessage.Participant identity;

        Connection(ServerMessage.Participant initial) {
            this.identity = initial;
        }

        ServerMessage.Participant identity() {
            return identity;
        }

        UUID authorId() {
            return identity.userId();
        }

        void adopt(ServerMessage.Participant next) {
            this.identity = next;
        }
    }

    /** Stable colours assigned deterministically by user id, so a returning user keeps their colour. */
    private static final String[] PALETTE = {
            "#e6194B", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
            "#42d4f4", "#f032e6", "#bfef45", "#fabed4", "#469990"
    };

    private final DocumentSessionRegistry registry;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    public OtterWebSocketHandler(DocumentSessionRegistry registry, UserRepository users, ObjectMapper objectMapper) {
        this.registry = registry;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Connection conn = new Connection(identityFor(null, null));
        Sinks.Many<ServerMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
        Map<UUID, Membership> memberships = new ConcurrentHashMap<>();

        Mono<Void> send = session.send(
                outbound.asFlux().map(msg -> session.textMessage(serialize(msg))));

        Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(text -> dispatch(text, conn, outbound, memberships))
                .doFinally(signal -> {
                    memberships.forEach((docId, m) -> {
                        m.session().leave(conn.authorId());
                        m.subscription().dispose();
                        registry.leave(docId);
                    });
                    memberships.clear();
                    outbound.tryEmitComplete();
                })
                .then();

        return Mono.zip(send, receive).then();
    }

    /** Builds the participant from the client-supplied id and name, falling back to a fresh anonymous one. */
    private ServerMessage.Participant identityFor(UUID userId, String displayName) {
        UUID id = userId != null ? userId : UUID.randomUUID();
        String colour = PALETTE[Math.floorMod(id.hashCode(), PALETTE.length)];
        String name = displayName != null && !displayName.isBlank()
                ? displayName.trim()
                : "Anon-" + id.toString().substring(0, 4);
        return new ServerMessage.Participant(id, name, colour);
    }

    private void dispatch(String text, Connection conn, Sinks.Many<ServerMessage> outbound,
            Map<UUID, Membership> memberships) {
        ClientMessage message;
        try {
            message = objectMapper.readValue(text, ClientMessage.class);
        } catch (Exception e) {
            return;
        }

        switch (message) {
            case ClientMessage.Join join -> {
                adopt(conn, join.userId(), join.displayName());
                join(conn, join.documentId(), outbound, memberships);
                memberships.get(join.documentId()).session().currentState().subscribe(outbound::tryEmitNext);
            }
            case ClientMessage.Leave leave -> {
                Membership m = memberships.remove(leave.documentId());
                if (m != null) {
                    m.session().leave(conn.authorId());
                    m.subscription().dispose();
                    registry.leave(leave.documentId());
                }
            }
            case ClientMessage.Op op -> {
                Membership m = memberships.get(op.documentId());
                if (m == null) {
                    return;
                }
                OperationEnvelope envelope = new OperationEnvelope(
                        op.clientOpId(),
                        op.documentId(),
                        conn.authorId(),
                        op.baseRevision(),
                        OperationMapper.toDomain(op.ops()));
                m.session().submit(envelope).subscribe(outbound::tryEmitNext);
            }
            case ClientMessage.Cursor cursor -> {
                Membership m = memberships.get(cursor.documentId());
                if (m != null) {
                    m.session().moveCursor(conn.authorId(), cursor.baseRevision(), cursor.anchor(), cursor.head());
                }
            }
            case ClientMessage.Resume resume -> {
                adopt(conn, resume.userId(), resume.displayName());
                join(conn, resume.documentId(), outbound, memberships);
                memberships.get(resume.documentId()).session()
                        .resume(resume.afterRevision())
                        .subscribe(frames -> frames.forEach(outbound::tryEmitNext));
            }
        }
    }

    private void adopt(Connection conn, UUID userId, String displayName) {
        conn.adopt(identityFor(userId, displayName));
        ServerMessage.Participant p = conn.identity();
        users.upsert(new UserRecord(p.userId(), p.displayName(), p.colour())).subscribe();
    }

    /** Joins the session for this document once per connection: subscribe, announce presence. */
    private void join(Connection conn, UUID documentId, Sinks.Many<ServerMessage> outbound,
            Map<UUID, Membership> memberships) {
        UUID authorId = conn.authorId();
        ServerMessage.Participant identity = conn.identity();
        memberships.computeIfAbsent(documentId, docId -> {
            DocumentSession ds = registry.join(docId);
            Disposable subscription = ds.broadcast()
                    .filter(frame -> !isOwnCursor(frame, authorId))
                    .subscribe(outbound::tryEmitNext);
            ds.join(identity);
            return new Membership(ds, subscription);
        });
    }

    /** A client never needs its own caret echoed back to it. */
    private static boolean isOwnCursor(ServerMessage message, UUID authorId) {
        return message instanceof ServerMessage.Cursor cursor && cursor.userId().equals(authorId);
    }

    private String serialize(ServerMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize server message", e);
        }
    }
}
