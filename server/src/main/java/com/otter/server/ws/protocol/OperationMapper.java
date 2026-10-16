package com.otter.server.ws.protocol;

import com.otter.ot.Delete;
import com.otter.ot.Insert;
import com.otter.ot.NoOp;
import com.otter.ot.Operation;
import com.otter.ot.OperationSequence;
import java.util.List;

public final class OperationMapper {

    private OperationMapper() {
    }

    public static OperationSequence toDomain(List<WireOp> wire) {
        return new OperationSequence(wire.stream().map(OperationMapper::toDomain).toList());
    }

    public static List<WireOp> toWire(OperationSequence sequence) {
        return sequence.operations().stream().map(OperationMapper::toWire).toList();
    }

    public static Operation toDomain(WireOp wire) {
        return switch (wire) {
            case WireOp.Insert i -> new Insert(i.position(), i.text());
            case WireOp.Delete d -> new Delete(d.position(), d.length());
            case WireOp.NoOp ignored -> new NoOp();
        };
    }

    public static WireOp toWire(Operation op) {
        return switch (op) {
            case Insert i -> new WireOp.Insert(i.position(), i.text());
            case Delete d -> new WireOp.Delete(d.position(), d.length());
            case NoOp ignored -> new WireOp.NoOp();
        };
    }
}
