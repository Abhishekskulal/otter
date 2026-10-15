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

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UUID authorId = UUID.randomUUID();
        Sinks.Many<ServerMessage> outbound = Sinks.many().unicast().onBackpressureBuffer();
        Map<UUID, Membership> memberships = new ConcurrentHashMap<>();

        Mono<Void> send = session.send(
                outbound.asFlux().map(msg -> session.textMessage(serialize(msg))));

        Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(text -> dispatch(text, authorId, outbound, memberships))
                .doFinally(signal -> {
                    memberships.forEach((docId, m) -> {
                        m.subscription().dispose();
                        registry.leave(docId);
                    });
                    memberships.clear();
                    outbound.tryEmitComplete();
                })
                .then();

        return Mono.zip(send, receive).then();
    }

    private void dispatch(String text, UUID authorId, Sinks.Many<ServerMessage> outbound,
            Map<UUID, Membership> memberships) {
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
                    Disposable subscription = ds.broadcast().subscribe(outbound::tryEmitNext);
                    ds.currentState().subscribe(outbound::tryEmitNext);
                    return new Membership(ds, subscription);
                });
            }
            case ClientMessage.Leave leave -> {
                Membership m = memberships.remove(leave.documentId());
                if (m != null) {
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
        }
    }

    private String serialize(ServerMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize server message", e);
        }
    }
}
