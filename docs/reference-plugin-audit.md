# GitHub 与 GitLab 参考插件能力审计

本审计于 2026-07-16 基于仓库中被 `.gitignore` 排除的只读参考副本完成：

- `jenkinsci/github-plugin`：`ff28c725e495433be809b0ecb22179975cd89ecd`；
- `jenkinsci/gitlab-plugin`：`fc54bd09eb2cd9b890b7b33668a169c9bf429558`。

对齐以 Jenkins 用户工作流和 CNB 当前公开 API 为边界，不复制参考插件的历史兼容代码，也不虚构
CNB 不存在的服务端能力。

## GitHub 插件对照

| 参考能力 | CNB 插件结论 |
|---|---|
| 多凭据、仓库识别与 webhook 触发 | 多 CNB Server、item-scoped 凭据、签名 bridge 和事件归档回补 |
| Git SCM 浏览器与 changelog 注解 | `CnbRepositoryBrowser` 与 `CnbChangeLogAnnotator`；明确 PR 文本和 40/64 位 SHA 才生成链接 |
| Push/PR SCM event 通知 | Branch Source 定向刷新；所有事件先用 CNB API 回读 revision |
| Commit Status | CNB 公开 API 只能读取，不能写；结果改用 PR comment 与 Commit/Tag annotations |
| Badge | GitHub 插件没有平台 Badge 能力；CNB 使用独立 List/Get/Upload API，并明确不冒充 Commit Status |
| 自动管理 GitHub hook | CNB Swagger 没有 webhook CRUD；使用仓库 `.cnb.yml` 中固定版本官方 bridge |

## GitLab 插件对照

| 参考能力 | CNB 插件结论 |
|---|---|
| Push、Tag、MR、评论触发与源/目标分支过滤 | 支持，并在 HMAC 后重新读取 ref、PR、评论和成员角色 |
| `[ci-skip]` | 默认开启；只使用 Commit API 的权威消息和实时 PR 描述 |
| 仅在 MR source revision 变化时构建 | 可选；状态来自持久 Queue/Run action，Controller 重启后有效 |
| 源/目标 branch push 触发开放 MR | `source`/`both` 模式；目标 push 逐个回读开放 PR |
| `RevisionParameterAction` checkout | 额外要求 Classic GitSCM remote 与源仓库等价，并使用配置中的精确 URI；不匹配时 fail closed |
| 设置构建描述 | 默认开启且可关闭；仅填充空描述 |
| 项目标签联想与 MR 标签过滤 | Classic 与 Multibranch 均使用仓库标签 API；保存警告不阻塞，运行时 fail closed |
| 评论触发最低成员角色 | RE2/J 完整匹配，并从目标仓库实时读取 Reporter/Developer/Master/Owner |
| queued/running/final 状态 | CNB 无原生写接口；使用幂等 PR comment 和 Commit/Tag annotation |
| Badge/状态图片 | GitLab 的 `status.png`/`status.json` 是 Jenkins 自身公开端点，不是 GitLab Badge 上传；CNB 直接使用平台 Badge API |
| PR/MR Pipeline 操作 | 支持强类型查询、创建、更新、review、comment、标签、assignee、reviewer 与合并 |
| 新增指定标签时强制构建 | 不支持：GitLab webhook 有 previous/current label diff，CNB 公开数据只有当前标签 |

## 优先级结论

GitHub 插件的核心是仓库链接、Push Trigger 和 Commit Status；GitLab 插件的核心是事件触发、状态上报
和少量 Pipeline 操作。CNB 插件在补 Badge 前已经提供 47 个 Pipeline symbol，并完成 Branch Source、
Organization Folder、传统 Job Trigger、Cause、环境变量、PR/Fork 信任和结果上报。因此后续不再以
“参考插件代码更多”为理由扩张外围 API，优先级固定为：

1. CNB 平台特有且用户可见的核心能力，例如 Badge；
2. 公开入口的直接安全风险和核心工作流回归；
3. 头像、命名策略等 UI 抛光；
4. 不实现 CNB OpenAPI 不存在的 webhook CRUD、Deployment 或原生 Commit Status 写入。

近期 signed redirect 修复处理的是会阻塞 Release Asset 上传和 Java 25 CI 的真实传输缺陷，不属于任意
边角扩张；但在 Badge 完成后，应冻结新的外围 endpoint 和防御性抽象，先完成发布、烟测与仓库保护。

## 有意强化的差异

- Webhook payload 只作为提示，签名通过后仍执行 CNB API 二次校验；
- Fork Jenkinsfile 默认不可信，代码 checkout 与 Jenkinsfile 来源分别决策；
- 所有外部 JSON 使用 `kotlinx.serialization` 私有 Wire DTO，不使用 Jackson 或开放 Map 边界；
- API、下载、分页、表单联想和队列操作都有资源上限与 fail-closed 行为；
- 删除、覆盖、关闭等破坏性 Pipeline 操作要求精确 `confirm`，webhook 永不自动执行写操作。

参考插件中的短板不会被照搬：GitHub 可在未配置 secret 时跳过签名校验；GitLab 可关闭公开项目端点
认证，并直接信任或记录 webhook payload。CNB 插件继续保留仓库级强制 HMAC、持久防重放、API 二次
校验、默认不信任 Fork Jenkinsfile、RE2/J 评论匹配和 item/URI-scoped 凭据隔离。
