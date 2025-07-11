-- doc_start
CREATE TABLE if not exists executing_workflows
(
    instance_id TEXT      NOT NULL,
    runtime_id  TEXT      NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    primary key (runtime_id, instance_id)
);
-- doc_end