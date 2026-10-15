CREATE TABLE documents (
    id                UUID PRIMARY KEY,
    title             TEXT        NOT NULL DEFAULT 'Untitled',
    owner_id          UUID        NOT NULL,
    current_revision  BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only. Rows are never updated or deleted except by retention compaction.
-- The composite primary key enforces the gapless total order per document.
CREATE TABLE operations (
    document_id   UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    revision      BIGINT      NOT NULL,
    client_op_id  UUID        NOT NULL,
    author_id     UUID        NOT NULL,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, revision)
);

-- A replayed operation after reconnect must not apply twice.
CREATE UNIQUE INDEX ux_operations_client_op
    ON operations (document_id, client_op_id);

CREATE TABLE document_snapshots (
    document_id   UUID        NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    revision      BIGINT      NOT NULL,
    content       TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, revision)
);

CREATE TABLE document_participants (
    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL,
    role         TEXT NOT NULL CHECK (role IN ('owner', 'editor', 'viewer')),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, user_id)
);
