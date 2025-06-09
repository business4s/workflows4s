-- doc_start
CREATE TABLE if not exists executing_workflows
(
    workflow_id   BIGINT    NOT NULL,
    workflow_type VARCHAR   NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    primary key (workflow_type, workflow_id)
);
-- doc_end