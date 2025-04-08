-- doc_start
CREATE TABLE if not exists workflow_journal
(
    event_id    SERIAL PRIMARY KEY,                          -- Auto-incrementing primary key
    workflow_id BIGINT    NOT NULL,                          -- The workflow ID
    event_data  BYTEA     NOT NULL,                          -- The event data (binary)
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP -- Timestamp for event creation
);

CREATE INDEX if not exists idx_workflow_id ON workflow_journal (workflow_id);
-- doc_end