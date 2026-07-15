# CNB public API capability matrix

This matrix was verified on 2026-07-15 against the official
[CNB Swagger document](https://api.cnb.cool/swagger.json) and CNB documentation.

The inspected document was Swagger 2.0, `info.version = 1.0`, with 190 paths. Its SHA-256 was
`7f37bbe1e90a388b199de86237115c9eb0d2cfd33197f098dcffb55445312475`.

| Capability | Public API | Plugin behavior |
|---|---|---|
| API authentication | Bearer access token | Supported |
| Git authentication | HTTPS Basic, username `cnb`, token password | Supported |
| Multiple/private instances | Separate API and Web endpoints | Supported |
| Repository and organization discovery | `/user/repos`, `/{slug}/-/repos`, groups/subgroups | Supported |
| Default branch | `GET /{repo}/-/git/head` | Supported |
| Branches | `GET /{repo}/-/git/branches` | Supported |
| Tags | `GET /{repo}/-/git/tags` | Supported |
| Pull requests and forks | `GET /{repo}/-/pulls` and detail endpoints | Supported |
| Lightweight file lookup | `GET /{repo}/-/git/contents/{file_path}` | Supported |
| Repository events | `GET /events/{repo}/-/{date}` | Polling fallback |
| Commit status read | `GET /{repo}/-/git/commit-statuses/{commitish}` | API client support; not surfaced in the Jenkins UI |
| Commit status write | No public POST/PUT/PATCH operation | Not claimed or emulated |
| Pull-request comments | POST and PATCH comment endpoints | Idempotent result comment |
| Commit metadata | GET/PUT/DELETE commit annotations | Namespaced build link/result metadata |
| Badge upload | Restricted to `security/tca` keys | Not used for Jenkins status |
| Webhook CRUD | No public hook/webhook paths | `.cnb.yml` bridge required |

## Pagination and compatibility

Most list endpoints use `page` and `page_size` without a total count or reliable pagination header.
The client requests pages until an empty page, rejects repeated pages, deduplicates stable resources,
and enforces maximum page/item limits.

Repository-event partitions are fetched through the API's short-lived object-storage URL. Automatic
HTTP redirects remain disabled: the plugin follows only a small, explicitly validated HTTPS chain
and omits `Authorization` from every object-storage request. Completed-hour cursors and event hashes
are persisted in Jenkins home so outages can be backfilled without repeatedly scheduling builds.

CNB's API path is not versioned. JSON parsing therefore tolerates new unknown fields and missing
optional fields. Each server profile is isolated for credentials, concurrency, timeouts, endpoint
policy, retries, and circuit state.

## Pull-request checkout

The API exposes source and target repository paths, refs, and SHAs. It does not guarantee a fetchable
pre-merged ref. HEAD builds fetch the source repository/ref. MERGE builds fetch the target SHA and
perform the merge locally with GitSCM. Fork trust controls which revision supplies the Jenkinsfile.

For result reporting, PR comments are written to the target repository while commit annotations are
written to the repository that owns the source SHA. CNB annotation PUTs merge by key; the plugin
writes only context-namespaced `jenkins/.../` keys.

## Primary official sources

- [OpenAPI calling convention](https://docs.cnb.cool/zh/develops/openapi.html)
- [Access tokens](https://docs.cnb.cool/zh/guide/access-token.html)
- [Git URL and authentication](https://docs.cnb.cool/zh/guide/git-access.html)
- [Build trigger rules](https://docs.cnb.cool/zh/build/trigger-rule.html)
- [Build environment variables](https://docs.cnb.cool/zh/build/build-in-env.html)
- [Repository events](https://docs.cnb.cool/zh/develops/openapi-event.html)
- [OAuth application scopes](https://docs.cnb.cool/zh/oauth/developer.html)
