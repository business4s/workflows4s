CREATE TABLE if not exists workflow_journal
(
    event_id    SERIAL PRIMARY KEY,                          -- Auto-incrementing primary key
    event_data  BYTEA     NOT NULL,                          -- The event data (binary)
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP -- Timestamp for event creation
);

