-- doc_start
CREATE TABLE if not exists executing_workflows
(
    instance_id TEXT      NOT NULL,
    template_id TEXT      NOT NULL,
    status      TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    wakeup_at   TIMESTAMP,
    tags        JSONB     NOT NULL DEFAULT '{}',
    PRIMARY KEY (template_id, instance_id)
);

CREATE INDEX if not exists idx_executing_workflows_status ON executing_workflows(status);
CREATE INDEX if not exists idx_executing_workflows_tags ON executing_workflows USING GIN(tags);
CREATE INDEX if not exists idx_executing_workflows_created_at ON executing_workflows(created_at);
CREATE INDEX if not exists idx_executing_workflows_updated_at ON executing_workflows(updated_at);
-- doc_end