package com.otter.server.api;

import com.otter.server.persistence.DocumentRepository;
import com.otter.server.persistence.DocumentSummary;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Document CRUD for the picker. No auth yet, so documents are addressable by anyone with the id. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    /** Placeholder owner until an auth model exists. */
    private static final UUID ANONYMOUS_OWNER = new UUID(0L, 0L);

    private final DocumentRepository documents;

    public DocumentController(DocumentRepository documents) {
        this.documents = documents;
    }

    public record CreateRequest(String title) {
    }

    public record RenameRequest(String title) {
    }

    @PostMapping
    public Mono<DocumentSummary> create(@RequestBody(required = false) CreateRequest body) {
        UUID id = UUID.randomUUID();
        return documents.create(id, titleOrDefault(body == null ? null : body.title()), ANONYMOUS_OWNER)
                .then(documents.findById(id));
    }

    @GetMapping
    public Flux<DocumentSummary> list() {
        return documents.list();
    }

    @GetMapping("/{id}")
    public Mono<DocumentSummary> get(@PathVariable UUID id) {
        return documents.findById(id).switchIfEmpty(Mono.error(notFound()));
    }

    @PatchMapping("/{id}")
    public Mono<DocumentSummary> rename(@PathVariable UUID id, @RequestBody RenameRequest body) {
        String title = body == null || body.title() == null ? "" : body.title().trim();
        if (title.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required"));
        }
        return documents.rename(id, title)
                .flatMap(updated -> updated == 0 ? Mono.error(notFound()) : documents.findById(id));
    }

    private static String titleOrDefault(String title) {
        return title == null || title.isBlank() ? "Untitled" : title.trim();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found");
    }
}
