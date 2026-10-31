# Finding read schema versions

The finding registry is now schema version 2. The version is returned by `GET /api/v1/entities/finding/schema` as `data.schemaVersion` and by finding list/detail responses as `meta.schemaVersion`.

Version 2 adds `organizationId` and `organizationName`, expands the default field projection, and changes the omitted-sort order to `createdAt DESC, id ASC`. An explicit sort remains in effect; the server continues to append `id ASC` as a stable tie-breaker when needed.

Clients should key cached finding schema metadata by the caller's tenant/authorization scope, entity key, and `schemaVersion`; do not share schema cache entries across scopes. The version is a representation tag, not an authorization token. When a list/detail response reports a schema version different from the cached one, the client should discard that cached schema and refresh the schema endpoint before validating later queries or responses. Clear the cache when the session, tenant, or authorization scope changes. A schema version change also invalidates saved query field selections and defaults tied to the earlier version.

The finding OpenAPI schemas reject undeclared fields. A client that strictly validates the old v1 response shape can reject the additional v2 fields. This change does not negotiate or continue serving a v1 response. Before enabling the new backend response for such clients, coordinate a client update and regenerate its contract types. The checked frontend source contains generated API types and contract fixtures, but no finding-schema cache or runtime response validator was identified there; consumers outside this repository still need to be inventoried before rollout.
