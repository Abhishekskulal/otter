package com.otter.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final DocumentSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public OtterWebSocketHandler(DocumentSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /** Distinct colours handed to members in join order; wraps around past the end. */
    private static final String[] PALETTE = {
            "#e6194B", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
            "#42d4f4", "#f032e6", "#bfef45", "#fabed4", "#469990"
    };

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        ServerMessage.Participant identity = newIdentity();
        UUID authorId = identity.userId();
        Sinks.Many<ServerMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
        Map<UUID, Membership> memberships = new ConcurrentHashMap<>();

        Mono<Void> send = session.send(
                outbound.asFlux().map(msg -> session.textMessage(serialize(msg))));

        Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(text -> dispatch(text, identity, outbound, memberships))
                .doFinally(signal -> {
                    memberships.forEach((docId, m) -> {
                        m.session().leave(authorId);
                        m.subscription().dispose();
                        registry.leave(docId);
                    });
                    memberships.clear();
                    outbound.tryEmitComplete();
                })
                .then();

        return Mono.zip(send, receive).then();
    }

    private static ServerMessage.Participant newIdentity() {
        UUID userId = UUID.randomUUID();
        String shortId = userId.toString().substring(0, 4);
        String colour = PALETTE[Math.floorMod(userId.hashCode(), PALETTE.length)];
        return new ServerMessage.Participant(userId, "Anon-" + shortId, colour);
    }

    private void dispatch(String text, ServerMessage.Participant identity, Sinks.Many<ServerMessage> outbound,
            Map<UUID, Membership> memberships) {
        UUID authorId = identity.userId();
        ClientMessage message;
        try {
            message = objectMapper.readValue(text, ClientMessage.class);
        } catch (Exception e) {
            return;
        }

        switch (message) {
            case ClientMessage.Join join -> {
                memberships.computeIfAbsent(join.documentId(), docId -> {
                    DocumentSession ds = registry.join(docId);
                    Disposable subscription = ds.broadcast()
                            .filter(frame -> !isOwnCursor(frame, authorId))
                            .subscribe(outbound::tryEmitNext);
                    ds.join(identity);
                    ds.currentState().subscribe(outbound::tryEmitNext);
                    return new Membership(ds, subscription);
                });
            }
            case ClientMessage.Leave leave -> {
                Membership m = memberships.remove(leave.documentId());
                if (m != null) {
                    m.session().leave(authorId);
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
                        authorId,
                        op.baseRevision(),
                        OperationMapper.toDomain(op.ops()));
                m.session().submit(envelope).subscribe(outbound::tryEmitNext);
            }
            case ClientMessage.Cursor cursor -> {
                Membership m = memberships.get(cursor.documentId());
                if (m != null) {
                    m.session().moveCursor(authorId, cursor.baseRevision(), cursor.anchor(), cursor.head());
                }
            }
            case ClientMessage.Resume resume -> {
                Membership m = memberships.computeIfAbsent(resume.documentId(), docId -> {
                    DocumentSession ds = registry.join(docId);
                    Disposable subscription = ds.broadcast()
                            .filter(frame -> !isOwnCursor(frame, authorId))
                            .subscribe(outbound::tryEmitNext);
                    ds.join(identity);
                    return new Membership(ds, subscription);
                });
                m.session().resume(resume.afterRevision())
                        .subscribe(frames -> frames.forEach(outbound::tryEmitNext));
            }
        }
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
