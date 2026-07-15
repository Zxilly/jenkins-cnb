# Architecture

The plugin is divided into deliberately narrow modules:

- `config` and `credentials`: multi-server profiles and secret references;
- `security`: endpoint and inbound-request policies;
- `api`: bounded, typed CNB OpenAPI client with no Jenkins job behavior;
- `scm`: SCM Source/Navigator, heads, revisions, traits, probes, and GitSCM assembly;
- `webhook` and `trigger`: signed event normalization, deduplication, SCM events, and traditional job
  causes;
- `status` and `pipeline`: build metadata/comment lifecycle and Pipeline-facing steps.

The API client accepts only relative API paths constructed by the plugin. Server endpoint validation
is performed at configuration and request time. Redirects are disabled except for the documented
repository-event object-storage flow; that flow revalidates every HTTPS destination and never
forwards the CNB bearer token. Credentials never come from payloads, and each server has independent
concurrency, retry, timeout, and circuit-breaker state.

Webhook delivery is an acceleration hint rather than the source of truth. Every event is matched to a
configured server/repository and the revision is re-read from CNB before Jenkins acts on it. SCM
indexing and repository-event polling repair dropped or out-of-order notifications. The polling
fallback persists a completed-hour cursor per repository, filters the broad event archive to code/PR
changes, and advances its cursor only after dispatch and dedup state are durable.

Traditional push/tag triggers also resolve the current branch or tag through the authenticated CNB
API before entering the Jenkins queue. A missing or superseded ref is a stale hint and is ignored; an
API or credential failure fails delivery so the bridge can retry after replay ownership is released.
When one delivery matches multiple traditional jobs, they share a single server/repository/ref
preflight, and the dispatcher emits no classic-job queue action until it succeeds. The SCM event is
delivered independently so each SCM Source can re-resolve the hint with its own item-scoped
credential; a classic trigger's global server credential cannot suppress Multibranch indexing.

CNB result reporting intentionally models two supported resources—pull-request comments and commit
annotations. It does not expose an abstraction called “commit status publisher,” because the public
CNB API cannot write that resource.

Commit annotations use context-derived `jenkins/.../` keys. CNB merges annotation PUTs by key, so the
plugin writes only its own namespaced keys and never performs a cross-producer read-modify-write.
