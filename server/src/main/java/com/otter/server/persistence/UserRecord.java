package com.otter.server.persistence;

import java.util.UUID;

/** A pseudonymous user: a stable id with a display name and colour, no credentials. */
public record UserRecord(UUID id, String displayName, String colour) {
}
