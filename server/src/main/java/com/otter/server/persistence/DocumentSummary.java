package com.otter.server.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Listing/metadata view of a document — everything the document picker needs, no operations. */
public record DocumentSummary(UUID id, String title, long revision, OffsetDateTime updatedAt) {
}
