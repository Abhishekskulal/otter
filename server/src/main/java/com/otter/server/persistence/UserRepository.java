package com.otter.server.persistence;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class UserRepository {

    private final DatabaseClient client;

    public UserRepository(DatabaseClient client) {
        this.client = client;
    }

    /** Creates the user on first contact, or refreshes the display name of a returning one. */
    public Mono<Void> upsert(UserRecord user) {
        return client.sql("""
                INSERT INTO users (id, display_name, colour)
                VALUES (:id, :displayName, :colour)
                ON CONFLICT (id) DO UPDATE
                    SET display_name = EXCLUDED.display_name, updated_at = now()
                """)
                .bind("id", user.id())
                .bind("displayName", user.displayName())
                .bind("colour", user.colour())
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<UserRecord> findById(UUID id) {
        return client.sql("SELECT id, display_name, colour FROM users WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> new UserRecord(
                        row.get("id", UUID.class),
                        row.get("display_name", String.class),
                        row.get("colour", String.class)))
                .one();
    }
}
