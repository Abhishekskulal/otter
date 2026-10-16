package com.otter.server.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientMessage.Join.class, name = "join"),
        @JsonSubTypes.Type(value = ClientMessage.Leave.class, name = "leave"),
        @JsonSubTypes.Type(value = ClientMessage.Op.class, name = "op"),
        @JsonSubTypes.Type(value = ClientMessage.Cursor.class, name = "cursor"),
        @JsonSubTypes.Type(value = ClientMessage.Resume.class, name = "resume")
})
public sealed interface ClientMessage
        permits ClientMessage.Join, ClientMessage.Leave, ClientMessage.Op,
                ClientMessage.Cursor, ClientMessage.Resume {

    UUID documentId();

    record Join(UUID documentId, UUID userId, String displayName) implements ClientMessage {
    }

    /** Reconnect catch-up request: replay everything committed after {@code afterRevision}. */
    record Resume(UUID documentId, long afterRevision, UUID userId, String displayName)
            implements ClientMessage {
    }

    record Leave(UUID documentId) implements ClientMessage {
    }

    record Op(UUID documentId, UUID clientOpId, long baseRevision, List<WireOp> ops)
            implements ClientMessage {
    }

    /** The sender's caret, expressed against the revision its positions were measured in. */
    record Cursor(UUID documentId, long baseRevision, int anchor, int head) implements ClientMessage {
    }
}
