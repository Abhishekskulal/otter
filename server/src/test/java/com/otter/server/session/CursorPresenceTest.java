package com.otter.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.otter.ot.Insert;
import com.otter.ot.Operation;
import com.otter.ot.OperationSequence;
import com.otter.server.ws.protocol.ServerMessage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CursorPresenceTest {

    private static final InMemoryDocumentStore STORE = new InMemoryDocumentStore();

    private static DocumentSession newSession() {
        return new DocumentSession(UUID.randomUUID(), STORE, 500);
    }

    @Test
    void staleCursorIsTransformedIntoTheCurrentRevisionFrame() {
        DocumentSession doc = newSession();
        List<ServerMessage> seen = subscribe(doc);
        commit(doc, new Insert(0, "Hello"), 0); // revision 1
        commit(doc, new Insert(0, "XYZ"), 1);   // revision 2 -> "XYZHello"

        UUID user = UUID.randomUUID();
        // A caret at "He|llo" (position 2), authored against revision 1, before the "XYZ" prefix.
        doc.moveCursor(user, 1, 2, 2);
        barrier(doc);

        ServerMessage.Cursor cursor = last(seen, ServerMessage.Cursor.class);
        assertThat(cursor.userId()).isEqualTo(user);
        assertThat(cursor.revision()).isEqualTo(2L);
        assertThat(cursor.anchor()).isEqualTo(5); // shifted right past "XYZ"
        assertThat(cursor.head()).isEqualTo(5);
    }

    @Test
    void joinAnnouncesPresenceAndSyncListsParticipants() {
        DocumentSession doc = newSession();
        List<ServerMessage> seen = subscribe(doc);

        ServerMessage.Participant member =
                new ServerMessage.Participant(UUID.randomUUID(), "Anon-1a2b", "#3cb44b");
        doc.join(member);

        ServerMessage.Sync sync = (ServerMessage.Sync) doc.currentState().block();
        assertThat(sync.participants()).containsExactly(member);
        assertThat(last(seen, ServerMessage.Presence.class).joined()).containsExactly(member);
    }

    @Test
    void leaveAnnouncesDeparture() {
        DocumentSession doc = newSession();
        ServerMessage.Participant member =
                new ServerMessage.Participant(UUID.randomUUID(), "Anon-9f9f", "#4363d8");
        doc.join(member);
        List<ServerMessage> seen = subscribe(doc);

        doc.leave(member.userId());
        barrier(doc);

        assertThat(last(seen, ServerMessage.Presence.class).left()).containsExactly(member.userId());
        ServerMessage.Sync sync = (ServerMessage.Sync) doc.currentState().block();
        assertThat(sync.participants()).isEmpty();
    }

    @Test
    void presenceSurvivesUntilTheUsersLastConnectionLeaves() {
        DocumentSession doc = newSession();
        List<ServerMessage> seen = subscribe(doc);
        ServerMessage.Participant member =
                new ServerMessage.Participant(UUID.randomUUID(), "Anon-2tab", "#f58231");

        doc.join(member); // first connection (e.g. tab A)
        doc.join(member); // second connection, same user (tab B)
        doc.leave(member.userId()); // tab B closes
        barrier(doc);

        // Still present after one of two connections leaves, and the join was announced only once.
        assertThat(((ServerMessage.Sync) doc.currentState().block()).participants()).containsExactly(member);
        assertThat(seen.stream().filter(m -> m instanceof ServerMessage.Presence p && !p.joined().isEmpty()).count())
                .isEqualTo(1);
        assertThat(seen.stream().anyMatch(m -> m instanceof ServerMessage.Presence p && !p.left().isEmpty()))
                .isFalse();

        doc.leave(member.userId()); // last connection closes
        barrier(doc);

        assertThat(last(seen, ServerMessage.Presence.class).left()).containsExactly(member.userId());
        assertThat(((ServerMessage.Sync) doc.currentState().block()).participants()).isEmpty();
    }

    private static List<ServerMessage> subscribe(DocumentSession doc) {
        List<ServerMessage> received = new CopyOnWriteArrayList<>();
        doc.broadcast().subscribe(received::add);
        return received;
    }

    /** Runs a state read to the end of the command queue, so earlier fire-and-forget commands have landed. */
    private static void barrier(DocumentSession doc) {
        doc.currentState().block();
    }

    private static void commit(DocumentSession doc, Operation op, long baseRevision) {
        doc.submit(new OperationEnvelope(
                UUID.randomUUID(), doc.documentId(), UUID.randomUUID(), baseRevision,
                new OperationSequence(List.of(op)))).block();
    }

    private static <T extends ServerMessage> T last(List<ServerMessage> messages, Class<T> type) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (type.isInstance(messages.get(i))) {
                return type.cast(messages.get(i));
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " broadcast");
    }
}
