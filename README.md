# CNB Integration for Jenkins

`cnb` is a production-oriented Jenkins integration for repositories hosted by
[CNB](https://cnb.cool). It provides CNB server profiles, credentials, Organization Folder and
Multibranch Pipeline discovery, traditional job triggers, signed event delivery, HTTPS checkout,
and build-result links through the CNB APIs that are publicly available.

- Jenkins plugin ID: `cnb`
- Java/Maven namespace: `dev.zxilly.jenkins.cnb`
- Source: [Zxilly/jenkins-cnb](https://github.com/Zxilly/jenkins-cnb)
- License: MIT

## Requirements

- Jenkins `2.568.1` or newer
- Java 21 or 25 on the controller and agents that load the plugin
- CNB access token with the minimum scopes described below
- Git plugin, SCM API, Branch API, Credentials, Plain Credentials, Pipeline Multibranch, and
  Pipeline Step API (installed automatically as plugin dependencies)

## Features

- Multiple CNB SaaS or private-deployment server profiles
- Secret Text, username/password, or dedicated CNB token credentials
- Organization Folder repository discovery, including nested CNB organizations
- Multibranch discovery for branches, tags, same-repository pull requests, and fork pull requests
- HEAD and local MERGE pull-request checkout strategies
- Trusted Jenkinsfile handling for fork pull requests
- Lightweight repository-content probing through the CNB OpenAPI
- HTTPS checkout using the Git plugin (`cnb` is the fixed Git username)
- Repository-bound, signed, restart-safe event ingress from the official `cnbcool/webhook` pipeline plugin
- Push, branch, tag, and trusted pull-request event handling
- Periodic repository-event polling and normal Jenkins indexing as consistency fallbacks
- Freestyle and Pipeline-compatible triggers, causes, and CNB environment variables
- Build lifecycle reporting through an idempotent pull-request comment and commit metadata
- JCasC-compatible global configuration
- Strict endpoint validation, redirect blocking, bounded responses, pagination guards, retry/backoff,
  per-server concurrency limits, and credential-safe logging

## Important CNB API boundary

CNB's public OpenAPI currently has no webhook CRUD endpoint and no endpoint that lets an external CI
system write native commit statuses/checks. This plugin does not pretend otherwise:

- CNB events are delivered by an explicit `.cnb.yml` bridge using the official
  `cnbcool/webhook:v1.0.2` image.
- Jenkins results are written as a CNB pull-request comment and/or commit annotations. These provide
  traceability but are not a CNB required status check.
- Periodic indexing remains enabled so a missed bridge request cannot permanently desynchronize
  Jenkins.

See [CNB API capabilities](docs/cnb-api-capabilities.md) for the verified endpoint matrix and
[Webhook bridge](docs/webhook-bridge.md) for the secure setup.

## Installation

Build the plugin with the checked-in Maven Wrapper:

```bash
./mvnw -B -ntp clean verify
```

Install `target/cnb.hpi` from **Manage Jenkins → Plugins → Advanced settings → Deploy Plugin**. A
controller restart is recommended after first installation.

Until the project is accepted into the `jenkinsci` GitHub organization, releases from this personal
repository are manual-install HPI files and are not distributed by the Jenkins Update Center.
Tagged releases attach a SHA-256 checksum and GitHub/Sigstore build-provenance attestation; verify an
artifact with `gh attestation verify cnb.hpi --repo Zxilly/jenkins-cnb` before manual installation.

## Credentials and minimum CNB permissions

Create separate credentials whenever operational policy allows it:

1. **Scan/checkout token**: `account-profile:r`, `account-engage:r`, `group-resource:r`,
   `repo-basic-info:r`, `repo-code:r`, `repo-pr:r`, and `repo-release:r`.
2. **Result-reporting token**: `repo-notes:rw`; add `repo-code:rw` only when commit annotations are
   enabled.
3. **Webhook HMAC secrets**: a separate high-entropy Jenkins Secret Text credential for every CNB
   repository, also configured as that repository's protected CNB secret. Each value must contain at
   least 32 UTF-8 bytes.

Do not grant `repo-manage:rw` or `repo-delete:rw`. CNB Git authentication uses the fixed username
`cnb` and the token as its password. SSH checkout is intentionally not offered because CNB does not
support it.

Secret Text credentials work for OpenAPI calls but cannot be passed to the Jenkins Git plugin. For
authenticated checkout, use the plugin's **CNB access token** credential type (fixed username
`cnb`) or a username/password credential whose username is `cnb`. A separate checkout credential can
be selected on each CNB Branch Source or Organization Folder.

For a fork pull request, annotation mode also requires the reporting identity to access the fork's
source repository. If that is outside your trust boundary, select **Pull request comment** mode; the
comment is written only to the target repository.

## Jenkins configuration

Open **Manage Jenkins → System → CNB** and add a server profile:

- **ID**: stable installation identifier used in the webhook URL, for example `cnb-cool`
- **Web URL**: `https://cnb.cool`
- **API URL**: `https://api.cnb.cool`
- **API credentials**: scan token credential ID
- **Result-reporting credentials**: optional write-scoped token credential ID
- **Repository webhook secrets**: one allowlisted repository path plus its current Secret Text
  credential; an optional previous credential is accepted only during rotation
- **Build result reporting**: pull-request comment, commit annotation, both, or disabled

HTTPS and public addresses are required by default. Private CNB installations require an explicit
administrator opt-in for private network endpoints; HTTP should only be enabled for isolated test
environments.

Webhook keys are intentionally not server-wide. A repository that knows its own bridge key cannot
sign a delivery for another configured repository.

A JCasC example is available at [docs/jcasc.yaml](docs/jcasc.yaml).

## Multibranch and Organization Folder

For a Multibranch Pipeline, add **CNB** as the Branch Source, select the server and credentials, and
enter the complete repository path (`group/subgroup/repository`). Configure discovery traits for:

- branches;
- tags;
- origin pull requests (HEAD and/or MERGE);
- fork pull requests and their trust policy.

For an Organization Folder, choose **CNB Organization**, select a server, and enter the organization
path. Nested organization discovery can be enabled independently. Secret repositories are excluded
because CNB does not permit cloning them.

Fork pull requests are untrusted by default. The plugin loads the Jenkinsfile from the target branch
unless the configured authority explicitly trusts the fork source.

## Traditional jobs and Pipeline

The **Build when CNB pushes a ref** trigger accepts authenticated CNB push and tag-push
events, then applies the configured branch/tag glob before scheduling a build. Builds receive a
persisted CNB cause and environment such as:

```text
CNB_SERVER_ID
CNB_EVENT
CNB_REPOSITORY
CNB_BRANCH
CNB_COMMIT
CNB_PULL_REQUEST_IID
CNB_PULL_REQUEST_SOURCE_BRANCH
CNB_PULL_REQUEST_SOURCE_SHA
```

For a live push or tag-push trigger, Jenkins re-resolves the named ref through the configured CNB
API and schedules the traditional job only when CNB reports the same revision. Missing or superseded
refs are ignored; API or credential failures return a retryable webhook failure instead of building
from unverified payload metadata.

The **Report build metadata to CNB** build step/publisher can be used in Freestyle and Pipeline jobs.
Automatic Branch Source builds also report lifecycle metadata when the server profile enables it.
For cross-repository pull requests, `repository` identifies the target repository that owns the PR,
while `commitRepository` identifies the fork that owns the source SHA.

The Pipeline symbol and exact parameters are available from Jenkins' built-in Pipeline Syntax page
after installation.

## Operations

- Rotate a repository's webhook secret by setting its new current credential while retaining the
  old credential in that repository mapping for one delivery window, then remove it.
- Keep event polling enabled. Completed UTC-hour cursors are persisted per repository and are
  backfilled after outages (up to 24 completed hours per polling pass); the open hour is re-read and
  event IDs are persisted for deduplication.
- Keep periodic Organization Folder reconciliation enabled. It performs a jittered full namespace
  scan every six hours by default, so a newly created repository is still discovered when its first
  bridge delivery is lost.
- A `401` means an expired/revoked token; `403` usually means either the user role or token scope is
  insufficient. The plugin retries only idempotent operations on transient network failures, 429,
  500, 502, 503, or 504.
- API and Web URLs are separate settings for private installations. Payload-provided endpoint URLs
  are never used for outbound requests.

## Development

```bash
./mvnw -B -ntp clean verify
./mvnw hpi:run
```

The build is pinned to Kotlin 2.4.10, Maven 3.9.16 (Wrapper 3.3.4), ktlint 1.8.0, JaCoCo 0.8.15,
Jenkins LTS 2.568.1, plugin parent `6.2211.v27f680c93c53`, and the `2.568.x` plugin BOM
`6687.v4253d9799d33`. It emits Java 21 bytecode and runs CI on Java 21 and 25. Every normal `verify`
run executes unit and Jenkins Test Harness integration tests, writes the coverage report to
`target/site/jacoco`, and enforces aggregate line coverage of at least 86%. Kotlin KAPT drives the
SezPoz/Stapler extension indexes. See [CONTRIBUTING.md](CONTRIBUTING.md) and
[architecture](docs/architecture.md).

## Security

Please do not open public issues for suspected vulnerabilities. Follow [SECURITY.md](SECURITY.md).
