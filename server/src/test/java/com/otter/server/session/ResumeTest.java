package com.otter.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.otter.ot.Insert;
import com.otter.ot.Operation;
import com.otter.ot.OperationSequence;
import com.otter.server.ws.protocol.ServerMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResumeTest {

    private static final InMemoryDocumentStore STORE = new InMemoryDocumentStore();

    private static DocumentSession newSession() {
        return new DocumentSession(UUID.randomUUID(), STORE, 500);
    }

    @Test
    void replaysOperationsAfterTheGivenRevisionThenMarker() {
        DocumentSession doc = newSession();
        commit(doc, new Insert(0, "a"), 0); // revision 1
        commit(doc, new Insert(1, "b"), 1); // revision 2
        commit(doc, new Insert(2, "c"), 2); // revision 3

        List<ServerMessage> frames = doc.resume(1).block(); // client last saw revision 1

        assertThat(frames).hasSize(3);
        assertThat(frames.get(0)).isInstanceOf(ServerMessage.Op.class);
        assertThat(((ServerMessage.Op) frames.get(0)).revision()).isEqualTo(2L);
        assertThat(((ServerMessage.Op) frames.get(1)).revision()).isEqualTo(3L);
        assertThat(frames.get(2)).isEqualTo(new ServerMessage.Resumed(doc.documentId(), 3L));
    }

    @Test
    void replaysNothingButTheMarkerWhenAlreadyCurrent() {
        DocumentSession doc = newSession();
        commit(doc, new Insert(0, "a"), 0); // revision 1

        List<ServerMessage> frames = doc.resume(1).block();

        assertThat(frames).singleElement().isEqualTo(new ServerMessage.Resumed(doc.documentId(), 1L));
    }

    @Test
    void fallsBackToFullSyncWhenRevisionIsOutsideRetainedHistory() {
        DocumentSession doc = newSession();
        commit(doc, new Insert(0, "a"), 0); // revision 1

        List<ServerMessage> frames = doc.resume(9).block(); // beyond current -> cannot catch up

        assertThat(frames).singleElement().isInstanceOf(ServerMessage.Sync.class);
    }

    private static void commit(DocumentSession doc, Operation op, long baseRevision) {
        doc.submit(new OperationEnvelope(
                UUID.randomUUID(), doc.documentId(), UUID.randomUUID(), baseRevision,
                new OperationSequence(List.of(op)))).block();
    }
}
