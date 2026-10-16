package com.otter.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.otter.server.persistence.DocumentRepository;
import com.otter.server.persistence.DocumentSummary;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    DocumentRepository documents;

    @Test
    void createReturnsTheNewDocument() {
        when(documents.create(any(), eq("Notes"), any())).thenReturn(Mono.empty());
        when(documents.findById(any()))
                .thenReturn(Mono.just(new DocumentSummary(UUID.randomUUID(), "Notes", 0, OffsetDateTime.now())));

        web.post().uri("/api/documents").bodyValue(Map.of("title", "Notes"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Notes")
                .jsonPath("$.revision").isEqualTo(0);
    }

    @Test
    void createWithoutTitleDefaultsToUntitled() {
        when(documents.create(any(), eq("Untitled"), any())).thenReturn(Mono.empty());
        when(documents.findById(any()))
                .thenReturn(Mono.just(new DocumentSummary(UUID.randomUUID(), "Untitled", 0, OffsetDateTime.now())));

        web.post().uri("/api/documents")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.title").isEqualTo("Untitled");
    }

    @Test
    void listReturnsSummaries() {
        when(documents.list())
                .thenReturn(Flux.just(new DocumentSummary(UUID.randomUUID(), "A", 3, OffsetDateTime.now())));

        web.get().uri("/api/documents")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(DocumentSummary.class).hasSize(1);
    }

    @Test
    void getUnknownDocumentIs404() {
        UUID id = UUID.randomUUID();
        when(documents.findById(id)).thenReturn(Mono.empty());

        web.get().uri("/api/documents/{id}", id)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void renameUnknownDocumentIs404() {
        UUID id = UUID.randomUUID();
        when(documents.rename(id, "New")).thenReturn(Mono.just(0L));

        web.patch().uri("/api/documents/{id}", id).bodyValue(Map.of("title", "New"))
                .exchange()
                .expectStatus().isNotFound();
    }
}
