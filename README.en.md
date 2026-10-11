# CNB Plugin

[简体中文](README.md) | English

[![Build](https://github.com/Zxilly/jenkins-cnb/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/Zxilly/jenkins-cnb/actions/workflows/build.yml)
[![Jenkins Security Scan](https://github.com/Zxilly/jenkins-cnb/actions/workflows/jenkins-security-scan.yml/badge.svg?branch=master)](https://github.com/Zxilly/jenkins-cnb/actions/workflows/jenkins-security-scan.yml)
[![License](https://img.shields.io/github/license/Zxilly/jenkins-cnb.svg)](LICENSE)

This plugin connects Jenkins to [CNB](https://cnb.cool) repositories. It triggers builds from code and pull
request events, discovers projects for Multibranch Pipelines and Organization Folders, and reports build
results to CNB.

The plugin short name is `cnb`.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Credentials and permissions](#credentials-and-permissions)
- [Global configuration](#global-configuration)
- [Jenkins job configuration](#jenkins-job-configuration)
- [Webhook](#webhook)
- [Build environment variables](#build-environment-variables)
- [Pipeline](#pipeline)
- [Build result reporting](#build-result-reporting)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [Support and contributing](#support-and-contributing)

## Features

- Connect to `cnb.cool` or private instances compatible with CNB OpenAPI, with multiple server profiles.
- Support Freestyle jobs, Pipelines, Multibranch Pipelines, and Organization Folders.
- Discover branches, tags, and pull requests from the same repository or forks.
- Check out pull request HEAD or MERGE revisions.
- Display CNB namespace avatars in Multibranch Pipelines and Organization Folders.
- Link recognized CNB pull request and commit references in Jenkins changelogs.
- Receive push, branch, tag, pull request, review, and comment events.
- Filter builds by branch, tag, pull request, draft status, labels, and commenter role.
- Report build status through pull request comments and commit or tag annotations.
- List, read, and upload CNB badges.
- Provide Pipeline steps for pull requests, CNB builds, releases, and release assets.
- Support JCasC, event polling, credential rotation, and recovery after Jenkins restarts.

## Requirements

- Jenkins `2.541.3` or later.
- Java 17, 21, or 25 on the Jenkins controller and agents running the plugin's workspace steps.
- HTTPS CNB web/API, webhook, and Git checkout URLs in production.
- HTTPS Git checkout. CNB does not support SSH checkout, so the plugin does not accept SSH clone URLs or
  SSH credentials.

Minimum dependency versions are recorded in the released HPI's `Plugin-Dependencies` manifest field.

## Installation

Install and update the plugin through the CNB Update Center:

`https://zxilly.github.io/jenkins-cnb/update-center.json`

1. Follow the [update site setup guide](docs/update-center.en.md) to install the public signing
   certificate and add the CNB update site.
2. Click **Check now** under **Manage Jenkins > Plugins**, search for **CNB Integration**, and install it.
3. Restart Jenkins after installation. Future versions appear in the plugin manager.

Keep the default Jenkins update site enabled for dependency plugins.

## Credentials and permissions

### Credential types

The plugin supports the following Jenkins credentials:

- **CNB access token**: used for both CNB API access and HTTPS Git checkout. The Git username is always `cnb`.
- **Secret Text**: used for CNB API access; cannot be used directly for Git checkout.
- **Username with password**: used for CNB API access. For checkout, set the username to `cnb` and the
  password to a CNB token.
- **Secret Text webhook key**: configured separately for each repository, with at least 32 UTF-8 bytes.

API access, checkout, and result reporting can use separate credentials. Do not reuse a CNB token as a
webhook HMAC key.

### Minimum permissions

| Purpose | CNB token scopes |
| --- | --- |
| Basic scanning and Organization Folders | `account-profile:r account-engage:r group-resource:r repo-basic-info:r repo-code:r repo-pr:r repo-release:r` |
| Reading pull request comments and reviews | `repo-notes:r` |
| Member trust and comment triggers | `repo-manage:r` |
| Reporting through pull request comments | `repo-notes:rw` |
| Reporting through commit annotations | `repo-code:rw` |
| Reporting through tag annotations | `repo-release:rw` |
| Reading badges | `repo-commit-status:r` |
| Uploading badges | `repo-commit-status:rw` |
| Pull request write operations | `repo-pr:rw`; comments, reviews, and replies also require `repo-notes:rw` |
| Reading releases | `repo-release:r` |
| Writing releases | `repo-release:rw` |
| CNB build status, stages, and logs | `repo-cnb-trigger:r` |
| Starting and stopping CNB builds | `repo-cnb-trigger:rw` |
| CNB build history | `repo-cnb-history:r` |

Do not grant `repo-delete:rw`. Grant only the scopes required by the features you enable.

## Global configuration

Open **Manage Jenkins > System > CNB** and add a CNB server.

| Field | Description |
| --- | --- |
| **ID** | A stable, unique server ID, such as `cnb-cool`. It is also part of the webhook URL. |
| **Name** | The display name in Jenkins. |
| **Web URL** | The CNB web URL, such as `https://cnb.cool`. |
| **API URL** | The CNB API URL, such as `https://api.cnb.cool`. |
| **API credentials** | Credentials for scanning and reading the CNB API. |
| **Result-reporting credentials** | Optional credentials for writing comments and annotations. Falls back to API credentials when unset. |
| **Repository webhook secrets** | A mapping of full repository paths to Secret Text credentials. |
| **Build result reporting** | Report through pull request comments, commit/tag annotations, both, or neither. |
| **Event polling** | Configure repository event polling and the webhook time window. |
| **Timeouts** | Configure connection and request timeouts. |

Click **Test scan/API credential** to verify the URLs, credentials, and user identity.

By default, result reporting writes both pull request comments and commit/tag annotations. If your API
credentials have only read access, configure separate result-reporting credentials or disable automatic
reporting.

To rotate a webhook key, set the new key as current and retain the old key as previous. Wait for one
webhook time window before removing the previous key.

Administrators must explicitly allow private network access when a private CNB instance uses internal
addresses. The insecure HTTP option is intended only for isolated local tests.

See [docs/jcasc.yaml](docs/jcasc.yaml) for a JCasC example.

## Jenkins job configuration

### Multibranch Pipeline

1. Create a **Multibranch Pipeline**.
2. Add **CNB repository** under **Branch Sources**.
3. Select the server, API credentials, and checkout credentials.
4. Enter the full repository path, such as `group/subgroup/repository`.
5. Configure discovery and filtering traits, then run indexing.

Available traits include:

- Branch discovery and filters for protected or locked branches.
- Tag discovery.
- HEAD/MERGE discovery for pull requests from the same repository.
- HEAD/MERGE discovery for fork pull requests, with draft, branch, label, and trust filters.
- Pull request builds triggered by authorized CNB comments.
- A context for automatic result reporting, or an option to disable automatic Branch Source reporting.

Fork pull requests are untrusted by default. Unless a different trust authority is explicitly configured,
Jenkins reads the Jenkinsfile from the target branch and uses the fork only for the source code to build.

### Organization Folder

1. Create an **Organization Folder**.
2. Add **CNB namespace**.
3. Select the server and credentials, and enter the CNB organization path.
4. Configure repository filters and discovery traits.

The navigator can discover sub-organizations recursively. Archived repositories are excluded by default,
and Secret repositories are not used as checkout sources.

### Freestyle and standalone Pipeline jobs

Under **Source Code Management > Git**, enter a CNB HTTPS clone URL and select credentials whose username
is `cnb`.

Enable **Build on CNB code or pull request events** to configure:

- Push, tag, branch, and pull request events.
- Filters for branches, tags, pull request source branches, and target branches.
- Draft/WIP status, required labels, and excluded labels.
- `[ci skip]`, `[ci-skip]`, and `[skip ci]` handling.
- Builds only when the pull request source SHA changes.
- Rebuilds of open pull requests when the source branch, or source and target branches, change.
- Cancellation of stale queued or running builds when a pull request is updated.
- Comment triggers restricted by RE2/J expressions and target repository member roles.
- Filling an empty build description from the CNB Cause, enabled by default.

Only `push` and `tag_push` are enabled by default. Pull request and comment events must be enabled
explicitly.

Freestyle jobs also support these Post-build Actions:

- **Report build metadata to CNB**
- **Perform a CNB pull request action**

Pull request actions run only after successful builds by default. Before disabling this restriction,
confirm that failed builds should also modify CNB. Destructive operations still require an explicit
confirmation value.

## Webhook

CNB does not currently provide an API for Jenkins to create repository webhooks automatically. Use the
pinned `cnbcool/webhook:v1.0.2` version in a trusted `.cnb.yml` and follow the
[webhook setup guide](docs/webhook.md) to forward its default flat `CNB_*` JSON payload directly.

The Jenkins endpoint is:

```text
https://<jenkins>/cnb-webhook/<server-id>/
```

To configure it:

1. Create a Secret Text credential in Jenkins containing at least 32 bytes.
2. Map the full repository path to that credential under the CNB server's **Repository webhook secrets**.
3. Store the same value as the protected CNB repository secret `JENKINS_CNB_WEBHOOK_SECRET`.
4. Reference only `${JENKINS_CNB_WEBHOOK_SECRET}` in `.cnb.yml`; do not commit the secret in plain text.
5. Ensure CNB can reach Jenkins over HTTPS.

The webhook accepts only JSON POST requests with an `X-CNB-Signature` header. Each repository uses a
separate HMAC key. Request bodies are limited to 1 MiB; larger requests return `413`.

The plugin validates the time window and delivery ID, and confirms the repository revision through the
CNB API before scheduling a build. Keep event polling enabled to refresh SCM Sources and recover missed
push/tag events for classic jobs. Pull request, review, and comment events still require webhooks.

## Build environment variables

Classic jobs triggered by webhooks receive `CNB_*` environment variables. Common variables include:

```text
CNB_SERVER_ID
CNB_EVENT
CNB_EVENT_URL
CNB_REPOSITORY
CNB_REPO_SLUG
CNB_REPO_NAME
CNB_BRANCH
CNB_BRANCH_SHA
CNB_BEFORE_SHA
CNB_COMMIT
CNB_COMMIT_SHORT
CNB_IS_TAG
CNB_BUILD_ID
CNB_BUILD_USER
CNB_BUILD_USER_EMAIL
```

Pull request, review, and comment events also provide:

```text
CNB_PULL_REQUEST_IID
CNB_PULL_REQUEST_TITLE
CNB_PULL_REQUEST_DESCRIPTION
CNB_PULL_REQUEST_SOURCE_REPOSITORY
CNB_PULL_REQUEST_SOURCE_BRANCH
CNB_PULL_REQUEST_SOURCE_SHA
CNB_PULL_REQUEST_TARGET_BRANCH
CNB_PULL_REQUEST_TARGET_SHA
CNB_PULL_REQUEST_MERGE_SHA
CNB_PULL_REQUEST_ACTION
CNB_PULL_REQUEST_IS_WIP
CNB_PULL_REQUEST_REVIEWERS
CNB_PULL_REQUEST_REVIEW_STATE
CNB_COMMENT_ID
CNB_COMMENT_BODY
CNB_COMMENT_TYPE
CNB_REVIEW_ID
CNB_REVIEW_DESCRIPTION
```

Variables that do not apply to the current event are empty strings.

## Pipeline

After installation, open **Pipeline Syntax** and select a CNB step to see its full parameters and return
values for the installed version.

Except for `cnbBuildMetadata`, CNB API steps support these optional context parameters:

```text
serverId repository pullRequestNumber sha credentialsId
```

`serverId`, `repository`, `pullRequestNumber`, and `sha` can be resolved from Multibranch context, a webhook
Cause, or the build environment. `credentialsId` is not read from environment variables. When omitted,
the step uses item-scoped SCM credentials or the server's API credentials.

### Step index

- General and commits: `cnbBuildMetadata`, `cnbCommit`, `cnbCommits`, `cnbCompareCommits`,
  `cnbCommitAnnotations`, `cnbCommitStatuses`.
- Badges: `cnbBadges`, `cnbBadge`, `cnbUploadBadge`.
- Pull request queries and updates: `cnbPullRequests`, `cnbPullRequest`, `cnbCreatePullRequest`,
  `cnbUpdatePullRequest`, `cnbMergePullRequest`, `cnbPullRequestAssignees`,
  `cnbAddPullRequestAssignees`, `cnbRemovePullRequestAssignees`, `cnbAddPullRequestReviewers`,
  `cnbRemovePullRequestReviewers`.
- Pull request comments, labels, and reviews: `cnbPullRequestComments`, `cnbPullRequestCommentById`,
  `cnbPullRequestComment`, `cnbUpdatePullRequestComment`, `cnbPullRequestLabelExists`,
  `cnbPullRequestLabels`, `cnbPullRequestCommits`, `cnbPullRequestFiles`,
  `cnbPullRequestStatuses`, `cnbPullRequestReviews`, `cnbPullRequestReviewComments`,
  `cnbReviewPullRequest`, `cnbReplyPullRequestReviewComment`.
- CNB builds: `cnbStartBuild`, `cnbBuildStatus`, `cnbStopBuild`, `cnbBuildHistory`,
  `cnbBuildStage`, `cnbDownloadBuildRunnerLog`.
- Releases and assets: `cnbReleases`, `cnbLatestRelease`, `cnbRelease`, `cnbReleaseByTag`,
  `cnbReleaseAsset`, `cnbReleaseAssetHead`, `cnbCreateRelease`, `cnbUpdateRelease`,
  `cnbDeleteRelease`, `cnbDeleteReleaseAsset`, `cnbUploadReleaseAsset`,
  `cnbDownloadReleaseAsset`.

### Badges

The plugin does not currently set build status badges automatically. CNB only permits uploads to
`security/tca`, a key reserved for Tencent Code Analysis. Jenkins cannot overwrite it as a general build
status badge.

TODO: Reconnect the retained automatic lifecycle reporting implementation when CNB provides a separate
general-purpose badge key.

The `cnbBadges`, `cnbBadge`, and manual `cnbUploadBadge` Pipeline steps remain available. Upload only when
you explicitly intend to write a TCA badge, and ensure `env.GIT_COMMIT` contains the full commit SHA after
checkout.

```groovy
def available = cnbBadges(repository: 'team/project')
def current = cnbBadge(
  repository: 'team/project',
  badge: 'security/tca',
  revision: 'latest'
)
def uploaded = cnbUploadBadge(
  repository: 'team/project',
  sha: env.GIT_COMMIT,
  key: 'security/tca',
  message: 'passed',
  link: env.BUILD_URL,
  latest: true
)

echo "![TCA](${uploaded.latestUrl})"
```

Badges are visual elements for READMEs and other displays, not CNB commit statuses or merge gates. The
CNB server rejects uploads to keys other than `security/tca`.

### Pull request example

This example is for pull request builds. Regular branch builds must supply `repository`,
`pullRequestNumber`, and `sha` explicitly.

```groovy
pipeline {
  agent any

  stages {
    stage('Build') {
      steps {
        sh './gradlew build'
      }
    }
  }

  post {
    success {
      cnbPullRequestComment(
        comment: "Jenkins ${env.BUILD_TAG} succeeded: ${env.BUILD_URL}"
      )
    }
  }
}
```

### CNB token binding

```groovy
withCredentials([cnbToken(credentialsId: 'cnb-api', variable: 'CNB_TOKEN')]) {
  sh 'curl --fail --header "Authorization: Bearer $CNB_TOKEN" https://api.cnb.cool/user'
}
```

Jenkins masks `CNB_TOKEN`. Do not include the token in URLs, build descriptions, or return values.

Deleting releases/assets, removing assignees/reviewers, clearing/removing/replacing labels, and closing
pull requests require a `confirm` parameter. The confirmation value must exactly match the target ID,
tag, or pull request number.

Workspace upload and download paths must be relative and cannot contain parent directory segments,
drive prefixes, or backslashes. Release asset uploads have a fixed 512 MiB limit. Release downloads and
runner log downloads can be further restricted with `maxBytes`.

## Build result reporting

Multibranch builds can automatically report queued, running, and final status. Classic jobs use
**Report build metadata to CNB**; Pipelines use `cnbBuildMetadata`.

The CNB server's **Build result reporting** setting selects the reporting destination:

- Pull request comments.
- Commit/tag annotations, with the target determined by the current build context.
- Both pull request comments and commit/tag annotations.
- Automatic reporting disabled.

Reporting credentials are selected in this order: explicit step or job credentials, result-reporting
credentials, then API credentials.

The `cnbSkipReporting` trait disables automatic reporting for a Branch Source. The `cnbReportingContext`
trait sets a default context. Explicit Pipeline steps and Freestyle publishers are not affected by
`cnbSkipReporting`.

CNB does not provide a native commit status API that Jenkins can write to. Reported comments and
annotations cannot replace required status checks on CNB protected branches.

## Known limitations

- Jenkins cannot create or delete webhooks through the CNB API. Run a `cnbcool/webhook` stage in `.cnb.yml`.
- Native CNB commit statuses can only be read, not written by Jenkins.
- CNB has not opened a general-purpose badge upload key. Automatic build status badges are pending
  server support.
- Badges are visual elements, not commit statuses or required checks.
- CNB does not provide a deployment API for external CI systems.
- CNB webhooks do not provide a trusted previous set of labels, so triggering only when a particular
  label is newly added is not supported.
- Git checkout supports HTTPS only, not SSH.

## Troubleshooting

### Webhook does not trigger a build

- Check that the server ID in the URL matches the global Jenkins configuration.
- Check that the repository path is mapped to the correct Secret Text credential.
- Check that `.cnb.yml` uses the pinned `cnbcool/webhook:v1.0.2` version.
- Confirm that the event is enabled in the job trigger and passes branch, tag, and draft filters.
- `413` means the webhook request body exceeds 1 MiB.
- Open **Manage Jenkins > CNB operational health** to inspect recent webhook results.

### CNB API errors

- `401` usually means the token is invalid or expired.
- `403` usually means the user role or token scopes are insufficient.
- `429` or `5xx` may indicate CNB rate limiting or a temporary service failure.

### Git checkout fails

- Use an HTTPS clone URL.
- Use **CNB access token**, or **Username with password** with the username set to `cnb`.
- Do not select Secret Text as a checkout credential.
- Confirm that the token has `repo-code:r` and access to the target repository.

For debugging, add a logger for `dev.zxilly.jenkins.cnb` under **Manage Jenkins > System Log**. Do not
paste tokens, webhook payloads, or HMAC keys into logs.

## Support and contributing

This plugin is maintained by the community. It does not represent commercial support from CNB or Jenkins.

- Questions and feature requests: [GitHub Issues](https://github.com/Zxilly/jenkins-cnb/issues)
- Security issues: [SECURITY.md](SECURITY.md)
- Contribution guidelines: [CONTRIBUTING.md](CONTRIBUTING.md)
- Version history: [CHANGELOG.md](CHANGELOG.md)
- License: [MIT](LICENSE)

Building from source requires JDK 21 or 25. See [CONTRIBUTING.md](CONTRIBUTING.md) for build environment
setup.

For local development:

```bash
./mvnw -B -ntp clean verify
./mvnw hpi:run
```
