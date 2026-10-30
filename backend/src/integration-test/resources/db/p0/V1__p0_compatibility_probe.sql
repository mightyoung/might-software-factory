CREATE TABLE p0_finding_projection (
    process_instance_id text PRIMARY KEY,
    tenant_id text NOT NULL,
    state text NOT NULL,
    version bigint NOT NULL
);

CREATE TABLE p0_finding_transition (
    process_instance_id text NOT NULL,
    transition_key text NOT NULL,
    PRIMARY KEY (process_instance_id, transition_key)
);

CREATE TABLE p0_outbox (
    event_key text PRIMARY KEY,
    event_type text NOT NULL
);

CREATE TABLE p0_tenant_probe (
    tenant_id text NOT NULL,
    item_key text NOT NULL,
    PRIMARY KEY (tenant_id, item_key)
);

ALTER TABLE p0_tenant_probe ENABLE ROW LEVEL SECURITY;
ALTER TABLE p0_tenant_probe FORCE ROW LEVEL SECURITY;

CREATE POLICY p0_tenant_probe_isolation ON p0_tenant_probe
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
