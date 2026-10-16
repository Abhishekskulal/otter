-- Pseudonymous users: a stable identity the browser keeps in localStorage and sends on connect.
-- No credentials yet; this persists the display name and colour, and gives documents a real owner.
CREATE TABLE users (
    id           UUID        PRIMARY KEY,
    display_name TEXT        NOT NULL,
    colour       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
