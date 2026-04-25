-- doc_start
CREATE TABLE if not exists workflow_registry
(
    template_id TEXT        NOT NULL,
    instance_id TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    wakeup_at   TIMESTAMPTZ NULL,
    tags        JSONB       NOT NULL DEFAULT '{}'::JSONB,
    primary key (template_id, instance_id)
);

CREATE INDEX if not exists idx_workflow_registry_wakeup
    ON workflow_registry (wakeup_at)
    WHERE wakeup_at IS NOT NULL;

CREATE INDEX if not exists idx_workflow_registry_tags
    ON workflow_registry USING gin (tags);

CREATE INDEX if not exists idx_workflow_registry_running_updated
    ON workflow_registry (updated_at)
    WHERE status = 'Running';
-- doc_end
