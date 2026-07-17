# CNB Webhook 接入

CNB 当前没有公开的仓库 Webhook CRUD API。Jenkins 通过 CNB Pipeline 中固定版本的官方
[`cnbcool/webhook`](https://cnb.cool/cnb/plugins/cnbcool/webhook) 插件接收事件通知。

Jenkins 接收 `cnbcool/webhook:v1.0.2` 默认发送的扁平 `CNB_*` JSON。

## 安全模型

1. 为每个仓库生成独立、至少 32 UTF-8 字节的高熵随机值，在 Jenkins 中保存为 Secret Text；不同
   仓库不能共用密钥。
2. 将同一值保存为受保护的 CNB 仓库密钥 `JENKINS_CNB_WEBHOOK_SECRET`。
3. Jenkins 地址必须使用有效证书的 HTTPS，并保持 `skip_verify: false` 和 `debug: false`。
4. PR 相关 stage 应放在可信目标分支的配置中。不要让不可信源分支修改目标 URL 或签名密钥。
5. 不要把 CNB Access Token、密码或其他凭据加入 payload。

请求使用 `X-CNB-Signature: sha256=<hex>`。Jenkins 先从未信任 payload 中读取 `CNB_REPO_SLUG`
选择仓库密钥，然后对原始 HTTP body 验签；验签前不会调度事件或信任其他字段。当前和 previous 两把
仓库密钥都可用于平滑轮换。

请求还受到以下约束：

- 仅接受 `application/json` POST，请求体最大 1 MiB。
- `CNB_BUILD_START_TIME` 必须处于 Server 配置的 Webhook 时间窗口内。
- 使用 `(server, CNB_REPO_SLUG, CNB_PIPELINE_ID)` 持久化去重。
- Web/API origin、仓库 URL、Git ref、SHA、PR IID 和事件类型均经过严格校验。

## CNB 配置

下面是 `main` 分支 `push` 事件的最小配置：

```yaml
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
            valid_response_codes:
              - 200
              - 202
              - 204
            skip_verify: false
            debug: false
```

插件默认生成的扁平 JSON 已包含 Jenkins 定位事件所需的 `CNB_EVENT`、`CNB_REPO_SLUG`、
`CNB_PIPELINE_ID`、ref/SHA、PR IID 和评论 ID 等字段。

对需要唤醒 Jenkins 的其他事件复用同一 stage。常见事件包括：

- 代码：`push`、`commit.add`、`branch.create`、`branch.delete`、`tag_push`。
- PR：`pull_request`、`pull_request.update`、`pull_request.target`、`pull_request.mergeable`、
  `pull_request.merged`。
- 评审和评论：`pull_request.approved`、`pull_request.changes_requested`、`pull_request.comment`。

`branch.create` 可能同时产生 push；两者同时配置时会产生不同 Pipeline ID 的两个通知。Jenkins 的 SCM
索引保持最终一致，但是否为两个事件都启动 Classic Job 仍由 Job 的事件过滤器决定。

## Jenkins 如何处理通知

Webhook 是经过签名的事件提示，不是仓库状态的权威来源：

1. Jenkins 使用 payload 中的仓库、事件、ref/SHA、PR IID 或评论 ID 定位候选对象。
2. 使用 Job 或 SCM Source 自己的 CNB API 凭据读取当前 branch、Tag、PR、评论、成员权限和需要的
   commit 信息。
3. 比较事件 revision 与 API snapshot；延迟、伪造或已经过时的通知不会进入队列。
4. Draft、标签、评论内容、作者权限和 CI skip 等策略使用 API 返回值判断，不依赖 payload 中的扩展字段。

因此不需要扩展 webhook 插件的默认字段白名单。`before SHA`、事件 action 和 Pipeline ID 等时点信息仍
来自 webhook；当前状态来自 CNB API。

## 密钥轮换

1. 在 Jenkins 和 CNB 中创建新密钥。
2. 在仓库映射中把新凭据设为 current，并把旧凭据 ID 暂时设为 previous。
3. 更新 CNB 仓库密钥。
4. 等待已配置的 Webhook 时间窗口结束后清空 previous。
