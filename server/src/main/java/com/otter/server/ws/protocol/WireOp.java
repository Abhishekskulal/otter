package com.otter.server.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = WireOp.Insert.class, name = "insert"),
        @JsonSubTypes.Type(value = WireOp.Delete.class, name = "delete"),
        @JsonSubTypes.Type(value = WireOp.NoOp.class, name = "noop")
})
public sealed interface WireOp permits WireOp.Insert, WireOp.Delete, WireOp.NoOp {

    record Insert(int position, String text) implements WireOp {
    }

    record Delete(int position, int length) implements WireOp {
    }

    record NoOp() implements WireOp {
    }
}
