package com.otter.server.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerMessage.Sync.class, name = "sync"),
        @JsonSubTypes.Type(value = ServerMessage.Op.class, name = "op"),
        @JsonSubTypes.Type(value = ServerMessage.Cursor.class, name = "cursor"),
        @JsonSubTypes.Type(value = ServerMessage.Presence.class, name = "presence"),
        @JsonSubTypes.Type(value = ServerMessage.Resumed.class, name = "resumed"),
        @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "error")
})
public sealed interface ServerMessage
        permits ServerMessage.Sync, ServerMessage.Op, ServerMessage.Cursor,
                ServerMessage.Presence, ServerMessage.Resumed, ServerMessage.Error {

    UUID documentId();

    record Participant(UUID userId, String displayName, String colour) {
    }

    record Sync(UUID documentId, long revision, String content, List<Participant> participants)
            implements ServerMessage {
    }

    record Op(UUID documentId, long revision, UUID clientOpId, UUID authorId, List<WireOp> ops)
            implements ServerMessage {
    }

    /** A member's selection, transformed into the server's {@code revision} frame before fan-out. */
    record Cursor(UUID documentId, UUID userId, long revision, int anchor, int head)
            implements ServerMessage {
    }

    /** Membership change: who joined and who left since the last presence frame. */
    record Presence(UUID documentId, List<Participant> joined, List<UUID> left)
            implements ServerMessage {
    }

    /** Terminates a reconnect replay: every op up to {@code revision} has been re-sent. */
    record Resumed(UUID documentId, long revision) implements ServerMessage {
    }

    record Error(UUID documentId, String code, String message) implements ServerMessage {
    }
}
