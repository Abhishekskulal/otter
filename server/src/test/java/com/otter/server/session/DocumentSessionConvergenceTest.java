package com.otter.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.otter.ot.Delete;
import com.otter.ot.Insert;
import com.otter.ot.Operation;
import com.otter.ot.OperationSequence;
import com.otter.ot.Ot;
import com.otter.server.ws.protocol.OperationMapper;
import com.otter.server.ws.protocol.ServerMessage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class DocumentSessionConvergenceTest {

    private static final InMemoryDocumentStore STORE = new InMemoryDocumentStore();

    private static DocumentSession newSession() {
        return new DocumentSession(UUID.randomUUID(), STORE, 500);
    }

    @Test
    void staleEditIsTransformedForwardAndBothClientsConverge() {
        DocumentSession doc = newSession();
        List<ServerMessage.Op> clientA = subscribe(doc);
        List<ServerMessage.Op> clientB = subscribe(doc);

        commit(doc, new Insert(0, "abcdef"), 0);
        commit(doc, new Delete(0, 2), 1);
        // Authored against revision 1, unaware of the delete at revision 2 — the simulated latency.
        commit(doc, new Insert(4, "Z"), 1);

        String serverContent = content(doc);
        assertThat(serverContent).isEqualTo("cdZef");
        assertThat(replay(clientA)).isEqualTo(serverContent);
        assertThat(replay(clientB)).isEqualTo(serverContent);
    }

    @Test
    void concurrentSamePositionInsertsOrderDeterministically() {
        DocumentSession doc = newSession();
        List<ServerMessage.Op> client = subscribe(doc);

        commit(doc, new Insert(0, "A"), 0);
        commit(doc, new Insert(0, "B"), 0);

        assertThat(content(doc)).isEqualTo("BA");
        assertThat(replay(client)).isEqualTo("BA");
    }

    @Test
    void replayedClientOpIdIsAcknowledgedWithoutReapplying() {
        DocumentSession doc = newSession();
        UUID clientOpId = UUID.randomUUID();
        OperationEnvelope envelope = new OperationEnvelope(
                clientOpId, doc.documentId(), UUID.randomUUID(), 0,
                new OperationSequence(List.of(new Insert(0, "hi"))));

        doc.submit(envelope).block();
        ServerMessage reack = doc.submit(envelope).block();

        assertThat(reack).isInstanceOf(ServerMessage.Op.class);
        assertThat(((ServerMessage.Op) reack).revision()).isEqualTo(1L);
        assertThat(content(doc)).isEqualTo("hi");
    }

    @Test
    void operationFromTheFutureIsRejected() {
        DocumentSession doc = newSession();

        ServerMessage reply = doc.submit(new OperationEnvelope(
                UUID.randomUUID(), doc.documentId(), UUID.randomUUID(), 5,
                new OperationSequence(List.of(new Insert(0, "x"))))).block();

        assertThat(reply).isInstanceOf(ServerMessage.Error.class);
        assertThat(((ServerMessage.Error) reply).code()).isEqualTo("REVISION_FROM_FUTURE");
    }

    private static List<ServerMessage.Op> subscribe(DocumentSession doc) {
        List<ServerMessage.Op> received = new CopyOnWriteArrayList<>();
        doc.broadcast().subscribe(message -> {
            if (message instanceof ServerMessage.Op op) {
                received.add(op);
            }
        });
        return received;
    }

    private static void commit(DocumentSession doc, Operation op, long baseRevision) {
        doc.submit(new OperationEnvelope(
                UUID.randomUUID(), doc.documentId(), UUID.randomUUID(), baseRevision,
                new OperationSequence(List.of(op)))).block();
    }

    private static String content(DocumentSession doc) {
        return ((ServerMessage.Sync) doc.currentState().block()).content();
    }

    private static String replay(List<ServerMessage.Op> broadcasts) {
        String state = "";
        for (ServerMessage.Op op : broadcasts) {
            state = Ot.apply(state, OperationMapper.toDomain(op.ops()));
        }
        return state;
    }
}
