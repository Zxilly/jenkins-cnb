# CNB 事件安全桥接

CNB 当前没有公开仓库 Webhook CRUD API。本插件支持的桥接方式是在 CNB Pipeline 中调用官方
[`cnbcool/webhook`](https://cnb.cool/cnb/plugins/cnbcool/webhook) 镜像，并固定使用 `v1.0.2`。

## 安全模型

1. 为每个仓库生成独立、至少 32 UTF-8 字节的高熵随机值，并在 Jenkins 中保存为 Secret Text
   凭据；不同仓库不能共用密钥。
2. 将同一值保存为受保护的 CNB 仓库密钥 `JENKINS_CNB_WEBHOOK_SECRET`。
3. 只在可信目标分支的配置中放置桥接 stage。PR 使用 `pull_request.target`，不要使用由不可信源
   分支控制的 `pull_request` 事件。
4. Jenkins 地址必须使用有效证书的 HTTPS；保持 `skip_verify: false` 和 `debug: false`。
5. 始终显式提供下面的最小模板。上游镜像的历史默认 payload 包含范围较广的环境字段，不能把默认
   payload 当作安全边界。

官方镜像按以下规则计算签名：

```text
X-CNB-Signature = "sha256=" + hex(HMAC-SHA256(secret, exact_request_body))
```

Jenkins 首先执行有大小和结构上限的严格解析，仅用于选择仓库白名单中配置的密钥。在信任任何字段
之前，插件会对原始请求正文逐字节验证 HMAC；每个仓库仅接受 current 密钥，或轮换期内配置的
previous 密钥。随后还会校验时间窗口、已配置的 CNB 源站，并按
`(server, repository, delivery_id)` 去重。防重放记录只保存哈希，持久化在
`JENKINS_HOME/cnb`；重启 Controller 不会重新打开已经关闭的时间窗口。

## Payload 协议

端点只接受 schema `dev.zxilly.jenkins.cnb.webhook.v1`。URL 中的 Server ID 和
`installation_id` 必须使用同一个稳定值。

```yaml
# .cnb.yml（可信目标分支）
master:
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
                "encoding": "cnbcool.webhook.v1.0.2-json-fragment",
                "installation_id": "cnb-cool",
                "delivery_id": "{{{ CNB_PIPELINE_ID }}}",
                "build_id": "{{{ CNB_BUILD_ID }}}",
                "occurred_at": "{{{ CNB_BUILD_START_TIME }}}",
                "event": "{{{ CNB_EVENT }}}",
                "event_url": "{{{ CNB_EVENT_URL }}}",
                "is_retry": "{{{ CNB_IS_RETRY }}}",
                "instance": {
                  "web_url": "{{{ CNB_WEB_ENDPOINT }}}",
                  "api_url": "{{{ CNB_API_ENDPOINT }}}"
                },
                "repository": {
                  "id": "{{{ CNB_REPO_ID }}}",
                  "slug": "{{{ CNB_REPO_SLUG }}}",
                  "url": "{{{ CNB_REPO_URL_HTTPS }}}"
                },
                "actor": {
                  "id": "{{{ CNB_BUILD_USER_ID }}}",
                  "username": "{{{ CNB_BUILD_USER }}}"
                },
                "ref": {
                  "name": "{{{ CNB_BRANCH }}}",
                  "sha": "{{{ CNB_BRANCH_SHA }}}",
                  "before": "{{{ CNB_BEFORE_SHA }}}",
                  "commit": "{{{ CNB_COMMIT }}}",
                  "is_tag": "{{{ CNB_IS_TAG }}}",
                  "new_commits_count": "{{{ CNB_NEW_COMMITS_COUNT }}}"
                },
                "pull_request": {
                  "id": "{{{ CNB_PULL_REQUEST_ID }}}",
                  "number": "{{{ CNB_PULL_REQUEST_IID }}}",
                  "title": "{{{ CNB_PULL_REQUEST_TITLE }}}",
                  "description": "{{{ CNB_PULL_REQUEST_DESCRIPTION }}}",
                  "proposer": "{{{ CNB_PULL_REQUEST_PROPOSER }}}",
                  "source_repo": "{{{ CNB_PULL_REQUEST_SLUG }}}",
                  "source_branch": "{{{ CNB_PULL_REQUEST_BRANCH }}}",
                  "source_sha": "{{{ CNB_PULL_REQUEST_SHA }}}",
                  "target_branch": "{{{ CNB_BRANCH }}}",
                  "target_sha": "{{{ CNB_PULL_REQUEST_TARGET_SHA }}}",
                  "merge_sha": "{{{ CNB_PULL_REQUEST_MERGE_SHA }}}",
                  "action": "{{{ CNB_PULL_REQUEST_ACTION }}}",
                  "wip": "{{{ CNB_PULL_REQUEST_IS_WIP }}}",
                  "reviewers": "{{{ CNB_PULL_REQUEST_REVIEWERS }}}",
                  "review_state": "{{{ CNB_PULL_REQUEST_REVIEW_STATE }}}",
                  "reviewed_by": "{{{ CNB_REVIEW_REVIEWED_BY }}}",
                  "last_reviewed_by": "{{{ CNB_REVIEW_LAST_REVIEWED_BY }}}",
                  "comment_id": "{{{ CNB_COMMENT_ID }}}",
                  "comment_body": "{{{ CNB_COMMENT_BODY }}}",
                  "comment_type": "{{{ CNB_COMMENT_TYPE }}}",
                  "comment_file_path": "{{{ CNB_COMMENT_FILE_PATH }}}",
                  "comment_range": "{{{ CNB_COMMENT_RANGE }}}",
                  "review_id": "{{{ CNB_REVIEW_ID }}}",
                  "review_description": "{{{ CNB_REVIEW_DESCRIPTION }}}"
                }
              }
```

固定版本 `v1.0.2` 必须使用三花括号，避免 Handlebars 对 CNB 值执行 HTML 转义。该镜像会先把经过
JSON 转义的值保存为字符串，再序列化外层对象；固定的 `encoding` marker 通知 Jenkins 在语义校验
前对字符串片段严格解码且只解码一次。不要改成双花括号、不要删除 marker，也不要把此 marker
用于其他编码器生成的 payload。

对需要唤醒 Jenkins 的其他可信事件复用同一固定版本 stage，尤其是 `pull_request.target` 和
`tag_push`。`branch.create` 同时也会产生 push；同时订阅两者可能形成语义重复事件。Jenkins 的
去重键基于 delivery，常规 SCM Source 索引负责最终一致性。

桥接 schema 接受完整的 CNB 代码/PR 事件集合：`push`、`commit.add`、`branch.create`、
`branch.delete`、`tag_push`、`pull_request`、`pull_request.update`、
`pull_request.approved`、`pull_request.changes_requested`、`pull_request.comment`、
`pull_request.target`、`pull_request.mergeable` 和 `pull_request.merged`。每个
`pull_request*` delivery 都必须包含 `pull_request` 对象。CNB 将源代码、评审和评论类事件划为
不可信事件；除非 Job 已与凭据和其他敏感资源有意隔离，否则不要添加这些事件的桥接 stage，也不要
在传统 Job Trigger 中启用它们。

`pull_request.comment` 还必须包含非空 `actor.username` 和 `pull_request.comment_id`。签名中的
`comment_body` 只是声明值：Jenkins 会通过 CNB OpenAPI 实时重新读取该评论，并要求评论 ID、完整
正文、实时作者、目标仓库成员角色、PR revision、配置的 RE2/J 表达式以及常规 Branch Source
策略全部一致。评论事件不经过通用 SCM 事件路径；只有显式配置评论触发 Trait 后，Multibranch
子项目才可能重建。

## 密钥轮换

1. 在 Jenkins 和 CNB 中创建新密钥。
2. 在该仓库映射中把新凭据设为 current，并把旧凭据 ID 暂时保留为 previous。
3. 更新 CNB 仓库密钥。
4. 等待已配置的 webhook 时间窗口结束后，清空 previous。

不要把 CNB Access Token 放进事件正文，也不要把它作为通用 webhook Bearer Token 发送。
