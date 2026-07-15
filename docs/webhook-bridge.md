# Secure CNB event bridge

CNB currently exposes no repository webhook CRUD API. The supported bridge uses the official
[`cnbcool/webhook`](https://cnb.cool/cnb/plugins/cnbcool/webhook) pipeline image, pinned to `v1.0.2`.

## Security model

1. Generate a distinct high-entropy value of at least 32 UTF-8 bytes for this repository and store
   it in Jenkins as a Secret Text credential.
2. Store the same value as a protected CNB repository secret named
   `JENKINS_CNB_WEBHOOK_SECRET`.
3. Put bridge stages only in trusted target-branch configuration. For pull requests use
   `pull_request.target`, not the untrusted source-branch `pull_request` event.
4. Keep `skip_verify: false` and `debug: false`.
5. Always provide an explicit template. The upstream image's historical default payload included
   broad environment fields and must not be relied on as a security boundary.

The official image calculates:

```text
X-CNB-Signature = "sha256=" + hex(HMAC-SHA256(secret, exact_request_body))
```

Jenkins performs a strict, bounded parse only to select the repository's allowlisted credential. It
then validates the HMAC over the exact raw request body before trusting any field, accepts the current
or previous key for that repository, verifies timestamp freshness and configured CNB origins, and
deduplicates `(server, repository, delivery_id)`. Replay claims are hash-only and persisted under
`JENKINS_HOME/cnb`; a controller restart does not reopen the freshness window.

## Payload contract

The endpoint accepts schema `dev.zxilly.jenkins.cnb.webhook.v1`. Configure the server ID in the URL
and `installation_id` to the same stable value.

```yaml
# .cnb.yml (target branch)
main:
  push:
    - stages:
        - name: notify-jenkins
          image: cnbcool/webhook:v1.0.2
          settings:
            urls:
              - https://jenkins.example.com/cnb-webhook/cnb-cool/
            method: POST
            content_type: application/json
            signature_header: X-CNB-Signature
            signature_secret: ${JENKINS_CNB_WEBHOOK_SECRET}
            valid_response_codes: [200, 202, 204]
            skip_verify: false
            debug: false
            template: |
              {
                "schema": "dev.zxilly.jenkins.cnb.webhook.v1",
                "installation_id": "cnb-cool",
                "delivery_id": "{{ CNB_PIPELINE_ID }}",
                "build_id": "{{ CNB_BUILD_ID }}",
                "occurred_at": "{{ CNB_BUILD_START_TIME }}",
                "event": "{{ CNB_EVENT }}",
                "event_url": "{{ CNB_EVENT_URL }}",
                "is_retry": "{{ CNB_IS_RETRY }}",
                "instance": {
                  "web_url": "{{ CNB_WEB_ENDPOINT }}",
                  "api_url": "{{ CNB_API_ENDPOINT }}"
                },
                "repository": {
                  "id": "{{ CNB_REPO_ID }}",
                  "slug": "{{ CNB_REPO_SLUG }}",
                  "url": "{{ CNB_REPO_URL_HTTPS }}"
                },
                "actor": {
                  "id": "{{ CNB_BUILD_USER_ID }}",
                  "username": "{{ CNB_BUILD_USER }}"
                },
                "ref": {
                  "name": "{{ CNB_BRANCH }}",
                  "sha": "{{ CNB_BRANCH_SHA }}",
                  "before": "{{ CNB_BEFORE_SHA }}",
                  "commit": "{{ CNB_COMMIT }}",
                  "is_tag": "{{ CNB_IS_TAG }}"
                },
                "pull_request": {
                  "id": "{{ CNB_PULL_REQUEST_ID }}",
                  "number": "{{ CNB_PULL_REQUEST_IID }}",
                  "title": "{{ CNB_PULL_REQUEST_TITLE }}",
                  "proposer": "{{ CNB_PULL_REQUEST_PROPOSER }}",
                  "source_repo": "{{ CNB_PULL_REQUEST_SLUG }}",
                  "source_branch": "{{ CNB_PULL_REQUEST_BRANCH }}",
                  "source_sha": "{{ CNB_PULL_REQUEST_SHA }}",
                  "target_branch": "{{ CNB_BRANCH }}",
                  "target_sha": "{{ CNB_PULL_REQUEST_TARGET_SHA }}",
                  "merge_sha": "{{ CNB_PULL_REQUEST_MERGE_SHA }}",
                  "action": "{{ CNB_PULL_REQUEST_ACTION }}",
                  "wip": "{{ CNB_PULL_REQUEST_IS_WIP }}"
                }
              }
```

Repeat the same pinned stage for trusted events that should wake Jenkins, especially
`pull_request.target` and `tag_push`. `branch.create` also produces a push, so subscribing to both can
create duplicate semantic events; Jenkins deduplication is delivery-based, while normal source
indexing provides final consistency.

## Secret rotation

1. Create the new Jenkins/CNB secret.
2. Select it as the repository mapping's current secret and retain the old ID as its previous secret.
3. Update the CNB repository secret.
4. After the configured webhook freshness window, clear the previous secret.

Never send a CNB access token in the event body or as a general webhook Bearer token.
