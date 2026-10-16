package com.otter.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.otter.ot.Insert;
import com.otter.ot.Operation;
import com.otter.ot.OperationSequence;
import com.otter.server.persistence.DocumentStore;
import com.otter.server.ws.protocol.ServerMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The M5 definition of done: operations typed into a document survive the loss of its in-memory
 * session. A real Postgres (Testcontainers) with Flyway-managed schema backs the store; a second
 * session for the same id must rebuild the exact text from the durable log.
 */
@SpringBootTest
@Testcontainers
class DocumentReloadIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://"
                + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    DocumentStore store;

    @Test
    void operationsSurviveSessionLoss() {
        UUID documentId = UUID.randomUUID();

        DocumentSession first = new DocumentSession(documentId, store, 500);
        commit(first, new Insert(0, "hello"), 0);
        commit(first, new Insert(5, " world"), 1);
        assertThat(contentOf(first)).isEqualTo("hello world");
        first.dispose();

        DocumentSession rebuilt = new DocumentSession(documentId, store, 500);
        ServerMessage reloadedMsg = rebuilt.currentState().block();
        assertThat(reloadedMsg).isInstanceOf(ServerMessage.Sync.class);
        ServerMessage.Sync reloaded = (ServerMessage.Sync) reloadedMsg;
        assertThat(reloaded.content()).isEqualTo("hello world");
        assertThat(reloaded.revision()).isEqualTo(2L);
        rebuilt.dispose();
    }

    @Test
    void reloadFoldsFromSnapshotAfterCompaction() {
        UUID documentId = UUID.randomUUID();

        // Snapshot on every operation: three snapshots, two retained, operations below the horizon pruned.
        DocumentSession first = new DocumentSession(documentId, store, 1);
        commit(first, new Insert(0, "a"), 0);
        commit(first, new Insert(1, "b"), 1);
        commit(first, new Insert(2, "c"), 2);
        first.dispose();

        DocumentSession rebuilt = new DocumentSession(documentId, store, 1);
        ServerMessage reloadedMsg = rebuilt.currentState().block();
        assertThat(reloadedMsg).isInstanceOf(ServerMessage.Sync.class);
        ServerMessage.Sync reloaded = (ServerMessage.Sync) reloadedMsg;
        assertThat(reloaded.content()).isEqualTo("abc");
        assertThat(reloaded.revision()).isEqualTo(3L);
        rebuilt.dispose();
    }

    private static void commit(DocumentSession session, Operation op, long baseRevision) {
        session.submit(new OperationEnvelope(
                UUID.randomUUID(), session.documentId(), UUID.randomUUID(), baseRevision,
                new OperationSequence(List.of(op)))).block();
    }

    private static String contentOf(DocumentSession session) {
        return ((ServerMessage.Sync) session.currentState().block()).content();
    }
}
