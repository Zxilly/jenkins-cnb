# CNB 公开 API 能力矩阵

本矩阵于 2026-07-16 对照 CNB 官方
[OpenAPI 文档](https://docs.cnb.cool/zh/develops/openapi.html)、在线 Swagger 与官方
[CNB Skills](https://docs.cnb.cool/zh/develops/skills.html) 重新核验。

- 在线 Swagger：2.0，`info.version=1.0`，190 个 path、297 个 definition；
- Swagger SHA-256：`7f37bbe1e90a388b199de86237115c9eb0d2cfd33197f098dcffb55445312475`；
- 官方 CNB Skills：`1.24.9`，提交 `00847e6e651dc70293d698a5c28332c7bc60ac2d`，
  共 256 份 endpoint 参考文档。

这里的“支持”指 Jenkins 源码托管、变更请求、CI 状态、构建和发布场景。CNB 的 Issue、知识库、
AI、计费、成员管理等平台管理 API 虽然存在，但不应被一个 Jenkins SCM 插件无边界地代理。

## 强类型与兼容边界

所有 CNB JSON 请求、响应和 webhook 都先进入私有 `kotlinx.serialization` `@Serializable`
Wire DTO，再映射成领域模型。API、SCM、触发器和结果上报层不接收 `JsonObject`、`JsonElement`、
`Map<String, Any?>` 或反射式数据绑定；Pipeline 的 `LinkedHashMap` 只存在于最终 Groovy/CPS 返回
适配器。

边界会校验并规范化：

- 仓库路径、namespace、Git ref、40/64 位完整对象 ID、PR/Release/Asset/Build ID；
- PR、Review、Commit Status、Build、Pipeline、Stage、可见性和仓库生命周期枚举；
- Badge 名、`latest`/8 位短 SHA revision、同源 URL，以及可空数值与显式 `0`；
- ISO-8601 时间、非负计数与持续时间、媒体类型、外部 URL、文件名和 workspace 相对路径。

未知 JSON 字段会被忽略以兼容 CNB 增量扩展；未知枚举、非法标量和不满足语义约束的必需字段会
fail closed。

## SCM、仓库和发现

| 能力 | CNB 公开 API | 插件行为 |
|---|---|---|
| API 认证 | Bearer access token | 支持 Secret Text、用户名/密码和 CNB Token；日志不输出 Token |
| Git 认证 | HTTPS Basic，用户名 `cnb`、密码为 Token | 支持；CNB 当前不提供 Git SSH checkout |
| 多实例 | Web/API endpoint 分离 | 支持 SaaS 与私有实例；默认拒绝 HTTP、userinfo、fragment 和内网目标 |
| 当前用户/连接测试 | `/user` | 支持，错误响应正文不会进入 Jenkins 日志 |
| 仓库详情/默认分支 | `/{repo}`、`/{repo}/-/git/head` | 支持；URL 从已配置 origin 和已校验 path 派生 |
| 可访问仓库 | `/user/repos` | Organization Folder 可发现当前凭据能读取的仓库 |
| namespace 仓库 | `/{slug}/-/repos` | 支持用户、组、组织和递归子组织发现 |
| 仓库状态/可见性 | `RepoStatus`、`Visibility` | 正常 Public/Private 仓库可构建；Archived 仅在显式启用时发现，Forking/Secret/未知值 fail closed |
| 分支 | list/get | 支持扫描、受保护/锁定过滤和 webhook 定向校验 |
| Tag | list/get | 支持 Tag discovery、构建和定向校验 |
| 仓库标签目录 | `GET /{repo}/-/labels` | Classic/Multibranch 标签联想与保存时警告；有界、item/凭据隔离缓存 |
| Commit | get/list/compare | 支持变更、作者、父提交和 changed-files 查询 |
| 仓库内容 | contents/raw | Jenkinsfile、文件和目录探测；二进制和大小有硬上限 |
| PR | list/get/batch | 支持同仓与 Fork PR、HEAD/MERGE、Draft、过滤和信任策略 |
| 成员权限 | repo member access-level | Fork Jenkinsfile 信任与评论触发按目标仓库实时角色 fail closed |
| 仓库动态 | `/events/{repo}/-/{date}` | 授权隔离的持久化轮询；刷新 SCM Source，并回补可精确重建的 Push/Tag |

## PR 与代码评审

| 能力 | CNB 公开 API | 插件行为 |
|---|---|---|
| 创建/更新 PR | POST/PATCH pulls | 强类型显式 Pipeline 操作；不会由 webhook 自动执行 |
| PR assignee | list/add/remove | 支持批量显式操作 |
| PR reviewer | add/remove | 支持批量显式操作 |
| PR 标签 | list/add/replace/remove/clear | Classic/Multibranch 实时过滤、只读查询和显式修改 |
| PR 评论 | list/get/create/update | 生命周期评论幂等更新；评论触发按 ID 回读并核对完整正文与作者 |
| PR Review | list/create | 支持 comment、approve、request changes 及行级评论请求 |
| Review comment | list/reply | 支持强类型行号、side、subject 和 reply-to ID |
| PR commits/files/statuses | GET | Pipeline 只读查询 |
| PR 合并 | `PUT /pulls/{number}/merge` | 显式 merge/squash/rebase；要求实时 source SHA，webhook 永不自动合并 |

所有 PR 写操作必须由 Job、Publisher 或 Pipeline 明确配置。Fork PR 默认不信任源仓库 Jenkinsfile；
除非 authority 明确信任，否则从目标分支读取 Jenkinsfile，避免不可信代码接触 Jenkins 凭据。

## Commit Status、构建和结果上报

| 能力 | CNB 公开 API | 插件行为 |
|---|---|---|
| 原生 Commit Status 读取 | Commit/PR status GET | Pipeline 只读查询，返回受控状态枚举 |
| 原生 Commit Status 写入 | 无 POST/PUT/PATCH | **不支持；独立 Badge 能力不会被伪装成原生状态** |
| Badge 列表 | `GET /{repo}/-/badge/list` | `cnbBadges`；兼容 Swagger envelope 与线上裸数组，返回同源 README URL |
| Badge JSON | `GET /{repo}/-/badge/git/{sha}/{badge}.json` | `cnbBadge`；`sha` 仅接受 `latest` 或 8 位短 SHA，可选 branch |
| Badge 上传 | `POST /{repo}/-/badge/upload` | `cnbUploadBadge` 显式写入；非幂等失败不重试，保留 `value=null` 与显式 `0` 的差异 |
| Commit annotations | GET/PUT/DELETE | 写入符合 CNB 字符约束的独立 `jenkins_..._` key；不覆盖其他生产者 |
| Commit annotations 批量读取 | POST `commit-annotations-in-batch` | `cnbCommitAnnotations` 强类型只读步骤；1..20 个 40 位 SHA、最多 5 个过滤 key |
| Tag annotations | GET/PUT/DELETE | Tag 构建写 Tag metadata，不污染 Commit key |
| CNB Build 启动 | `POST /build/start` | 仅显式 step，事件只接受 `api_trigger*`，避免递归 |
| Build 状态 | `GET /build/status/{sn}` | 有界单次查询；中断会传播到 Jenkins |
| Stage 详情 | `GET /build/logs/stage/...` | 强类型状态、时序和有界日志行 |
| Runner 日志 | `GET /build/runner/download/log/...` | 有界流式下载；不把日志整体载入 Controller 内存 |
| Build 停止 | `POST /build/stop/{sn}` | 仅显式 step；不会由 webhook 自动调用 |
| Build 历史 | `GET /build/logs` | 有界过滤/分页查询；不提供删除能力 |

CNB 没有可写的原生 Commit Status，因此 Jenkins 结果使用 PR 评论以及 Commit/Tag annotations。
queued、running、final、重试和 Controller 重启恢复共享同一个持久化幂等状态机。

Badge 是独立的 README/视觉展示能力：读取需要 `repo-commit-status:r`，上传需要
`repo-commit-status:rw`。当前官方文档只明确列出可上传 key `security/tca`；插件校验 key 的路径结构，
由 CNB 服务端执行动态 allowlist，不会硬编码或自动发布未文档化的 `jenkins/*` key。上传返回的
Commit/latest URL 只允许落在配置的 CNB Web origin 和目标仓库 Badge 路径下。

## Release 与附件

| 能力 | CNB 公开 API | 插件行为 |
|---|---|---|
| Release 列表/latest/ID/tag | GET releases | Pipeline 强类型只读步骤 |
| 创建/更新 Release | POST/PATCH | 显式步骤；`make_latest` 使用枚举 |
| 删除 Release | DELETE | `confirm` 必须精确匹配 ID 或 tag |
| Asset 详情/HEAD | GET/HEAD | 不向 Pipeline 暴露 CNB 返回的 signed/API/browser URL |
| Asset 上传 | upload-url + PUT + confirmation | workspace 流式、固定长度、不可自动重试非幂等传输；Bearer 不发往对象存储 |
| Asset 下载 | download + redirect | workspace 临时文件、大小上限、长度核对和原子发布；覆盖必须显式开启 |
| Asset 删除 | DELETE | `confirm` 必须精确匹配 Asset ID |

每个下载重定向和 signed upload URL 都重新验证 scheme、host、userinfo、fragment、地址类型和长度。
跨 origin 后立即移除 Authorization；上传确认 URL 必须回到配置的 CNB API origin 和精确资源路径。

## Webhook 事实边界

当前 Swagger 没有 hook/webhook CRUD。仓库 `.cnb.yml` 必须调用官方固定版本
`cnbcool/webhook:v1.0.2`，向 Jenkins 发送本插件定义的仓库级 HMAC 协议；它不是 CNB 原生
webhook payload。

支持的代码事件：

- `push`、`commit.add`、`branch.create`、`branch.delete`、`tag_push`；
- `pull_request`、`pull_request.update`、`pull_request.target`；
- `pull_request.approved`、`pull_request.changes_requested`；
- `pull_request.mergeable`、`pull_request.merged`、`pull_request.comment`。

签名覆盖精确原始 body。通过 HMAC、时间窗、origin、schema 和持久化防重放后，插件仍从 CNB API
重新读取 ref/PR revision。评论触发还要求显式 RE2/J 完整匹配、可寻址 comment ID、实时正文与
作者一致，以及目标仓库的最低成员角色；默认最低角色为 Developer。

传统 Job Trigger 还对齐 GitLab 插件的两项提交过滤能力：默认从 Commit API 回读头提交（PR 还检查
实时 description）并识别 `[ci-skip]`、`[ci skip]`、`[skip ci]`；也可选择仅在 PR source SHA 相比同一 Job/PR 最近一次
排队或已记录构建发生变化时触发。前者不信任事件中的提交消息，后者复用持久 `CnbQueueAction`，无需
另建可竞态的可变 webhook 状态。

目标分支 push 可列出开放 PR，并在首次队列修改前逐个回读 PR、目标 branch、源 branch、标签和提交。
Classic Job 还要求 GitSCM 中存在与源仓库等价的 remote；否则该 PR fail closed。构建开始后可用
有界 CNB Cause 填充空描述，Git changelog 可生成目标 PR 与源仓库 Commit 链接。

GitLab 的“新增指定标签时强制构建”依赖 webhook 的 previous/current label diff。CNB 当前公开
OpenAPI、事件归档和官方 bridge 没有 previous labels；只读取当前标签无法区分新增、旧标签、漏事件或
Controller 重启，因此本插件明确不提供不可靠的近似实现。

## 分页、重试和网络安全

多数列表仅提供 `page`、`page_size`，没有可靠总数或分页 header。客户端会：

- 读到空页后停止，拒绝重复页，对稳定 ID 去重；
- 限制最大页数、条目数、响应字节数、并发和排队等待；
- 对 path segment、Unicode、斜杠和 Git ref 分别编码；
- 仅对网络错误、`429/500/502/503/504` 重试幂等请求，并尊重有界 `Retry-After`；
- 普通 API 禁止重定向；少数对象存储流程只跟随重新校验的有限重定向；
- 每个 Server 独立维护凭据、超时、限流、退避和 circuit 状态。

Payload 和 API response 中提供的 endpoint 永远不会覆盖管理员配置的出站 origin。

## 明确不实现

以下能力不属于 Jenkins SCM/CI 集成，或其风险明显高于收益：

- 仓库、组织、成员的创建、删除、转移、归档和权限修改；
- 分支/Tag 创建删除、保护规则、锁、push limit 与流水线全局设置修改；
- Issue、PR 富文本附件、知识库、workspace、AI 请求/用量、计费和 TAPD 操作；
- 删除 CNB Build 日志、同步 CNB crontab；
- 自动上传 Commit attachment；构建制品使用有生命周期和摘要字段的 Release Asset；
- 以未文档化的 Badge key 自动发布 Jenkins 生命周期，或把 Badge 宣称为原生 Commit Status/合并门禁；
- 自动创建 webhook 或 deployment：当前公开 Swagger 没有对应写接口。

这些边界不会阻止以后增加独立、最小权限且可审计的扩展，但不会把一个生产 Jenkins Controller
变成 CNB 全平台管理员。

## 主要官方资料

- [OpenAPI 调用约定](https://docs.cnb.cool/zh/develops/openapi.html)
- [仓库动态 API](https://docs.cnb.cool/zh/develops/openapi-event.html)
- [访问令牌](https://docs.cnb.cool/zh/guide/access-token.html)
- [Git URL 与认证](https://docs.cnb.cool/zh/guide/git-access.html)
- [云原生构建触发规则](https://docs.cnb.cool/zh/build/trigger-rule.html)
- [默认构建环境变量](https://docs.cnb.cool/zh/build/env.html)
- [OAuth scope](https://docs.cnb.cool/zh/oauth/developer.html)
