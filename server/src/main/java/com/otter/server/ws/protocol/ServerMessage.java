package com.otter.server.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerMessage.Sync.class, name = "sync"),
        @JsonSubTypes.Type(value = ServerMessage.Op.class, name = "op"),
        @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "error")
})
public sealed interface ServerMessage permits ServerMessage.Sync, ServerMessage.Op, ServerMessage.Error {

    UUID documentId();

    record Participant(UUID userId, String displayName, String colour) {
    }

    record Sync(UUID documentId, long revision, String content, List<Participant> participants)
            implements ServerMessage {
    }

    record Op(UUID documentId, long revision, UUID clientOpId, UUID authorId, List<WireOp> ops)
            implements ServerMessage {
    }

    record Error(UUID documentId, String code, String message) implements ServerMessage {
    }
}
