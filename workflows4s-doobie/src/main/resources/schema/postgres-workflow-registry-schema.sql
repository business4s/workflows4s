-- doc_start
CREATE TABLE if not exists executing_workflows
(
    instance_id TEXT      NOT NULL,
    template_id  TEXT      NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    primary key (template_id, instance_id)
);
-- doc_end