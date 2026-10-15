package com.otter.server.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientMessage.Join.class, name = "join"),
        @JsonSubTypes.Type(value = ClientMessage.Leave.class, name = "leave"),
        @JsonSubTypes.Type(value = ClientMessage.Op.class, name = "op")
})
public sealed interface ClientMessage permits ClientMessage.Join, ClientMessage.Leave, ClientMessage.Op {

    UUID documentId();

    record Join(UUID documentId) implements ClientMessage {
    }

    record Leave(UUID documentId) implements ClientMessage {
    }

    record Op(UUID documentId, UUID clientOpId, long baseRevision, List<WireOp> ops)
            implements ClientMessage {
    }
}
