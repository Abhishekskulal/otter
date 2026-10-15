package com.otter.server.persistence;

import java.util.UUID;

public record SnapshotRecord(UUID documentId, long revision, String content) {
}
