# Jenkins CNB 集成插件

[![Build](https://github.com/Zxilly/jenkins-cnb/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/Zxilly/jenkins-cnb/actions/workflows/build.yml)
[![Jenkins Security Scan](https://github.com/Zxilly/jenkins-cnb/actions/workflows/jenkins-security-scan.yml/badge.svg?branch=master)](https://github.com/Zxilly/jenkins-cnb/actions/workflows/jenkins-security-scan.yml)

这是一个面向生产环境的 Jenkins 插件，用于集成 [CNB](https://cnb.cool) 代码仓库、Pull
Request、仓库事件和构建结果。插件同时支持传统 Job、Pipeline、Multibranch Pipeline 和
Organization Folder，并以 CNB 当前公开 OpenAPI 的能力为边界。

- Jenkins 插件短名称：`cnb`
- Maven `groupId` / Java 包名：`dev.zxilly.jenkins.cnb`
- 源码仓库：[Zxilly/jenkins-cnb](https://github.com/Zxilly/jenkins-cnb)
- 默认分支：`master`
- 许可证：MIT

## 运行要求

- Jenkins `2.541.3` 或更高版本；
- Controller 以及加载本插件的 Agent 使用 Java 17、21 或 25；
- 按最小权限创建的 CNB 访问令牌；
- 生产环境的 CNB Web/API、Jenkins Webhook 和 Git checkout 端点全部使用 HTTPS；
- Git、SCM API、Branch API、Credentials、Plain Credentials、Pipeline Multibranch 和
  Pipeline Step API 等依赖插件；从本地上传 HPI 前必须先安装其 Manifest 声明的依赖。

## 主要能力

- 配置多个 CNB SaaS 或私有部署实例，分别设置 Web URL、API URL、超时和凭据，并实施独立并发上限；
- 使用 Secret Text、用户名/密码或专用的 CNB Token 凭据；
- 发现组织、子组织和当前凭据可访问的仓库；
- 发现分支、Tag、同仓 PR 和 Fork PR；
- 在 Classic 与 Multibranch 配置页按 item-scoped 凭据提供仓库标签联想和非阻塞远端校验；
- 支持 PR HEAD 与本地 MERGE 两种 checkout 策略；
- 对 Fork PR 使用显式 Jenkinsfile 信任策略，默认不信任 Fork；
- 通过 CNB OpenAPI 进行轻量级 Jenkinsfile/目录探测；
- 通过 Jenkins Git 插件执行 HTTPS checkout，CNB Git 用户名固定为 `cnb`；
- 接收 `push`、分支、Tag 和 PR 生命周期事件；
- 可在源分支或目标分支更新时重新构建开放 PR，并对错误或不可获取的 Git remote fail closed；
- 使用仓库级 HMAC 密钥、时间窗口、持久化防重放和 API 二次校验保护 webhook；
- 使用 CNB 仓库动态轮询刷新 SCM Source，并为传统 Job 回补可精确重建的 Push/Tag 事件；
- 为传统 Job 和 Pipeline 提供 Cause、环境变量及分支/事件过滤；
- 为 Classic 构建设置可关闭的 CNB Cause 描述，并将 changelog 中的 PR 与 Commit 变为 CNB 链接；
- 通过 PR 评论与 Commit/Tag annotations 上报 Jenkins 排队、运行和最终结果；
- 提供 PR、CNB Build、Release 及 Release Asset 的强类型 Pipeline 读写步骤；
- 使用 Ktor Client 3.5.1、ContentNegotiation 和 `kotlinx.serialization` DTO 校验 CNB
  OpenAPI 与 Webhook 的 JSON 请求/响应边界；常规成功响应真实经过 Ktor converter，分页、错误体和
  下载保留可计数或可流式处理的原始字节；
- 支持 JCasC、凭据轮换、重启恢复、限流重试和安全日志。

## 强类型与不可信边界

插件不会把 CNB 响应直接作为 `Map<String, Any?>`、反射式对象或任意 JSON 暴露给
Jenkins。OpenAPI wire DTO 与 Webhook payload 均使用 `@Serializable` 类型；只在 CNB 同一标量
可能返回字符串、数字或布尔值时使用受限的自定义标量 serializer。Wire DTO 转换为领域模型时还会
再次校验仓库路径、ref、完整的 40/64 位 Git object ID、枚举、时间戳、URL、集合数量和文本长度。

JSON 解析拒绝宽松语法、特殊浮点数和不兼容字段类型；未知字段只为 CNB 向前兼容而忽略。所有 API
URL 都从管理员配置的 CNB origin 和经过编码的强类型路径参数生成。Ktor 管理 HTTP、
`HttpTimeout`、ContentNegotiation 和客户端生命周期；Apache5 只作为 Ktor 官方 engine，以保留实际
socket 建连时的 DNS resolver 安全边界。

Ktor 自动重定向始终关闭。Release/Build 下载中的重定向或预签名 URL 视为不可信输入，
逐跳执行 HTTPS/公网策略，且不转发 CNB `Authorization` 或 JSON 内容协商头。Pipeline 最终只获得经过校验、
可由 CPS 安全持久化的值。

## CNB API 能力边界

CNB 当前公开 OpenAPI 没有 Webhook CRUD、原生 Commit Status/Check 写接口，也没有可供外部 CI
使用的 Deployment API。本插件不会伪造这些能力：

- 仓库事件由 `.cnb.yml` 调用官方 `cnbcool/webhook` 镜像转发到 Jenkins；
- Jenkins 结果写入 PR 评论以及带 `jenkins_..._` 命名空间的 Commit/Tag annotations；
- 原生 Commit Status 只读，可供 Pipeline 查询，但不能由 Jenkins 写入；
- Release/Asset 和 CNB Build 使用 CNB 已公开的强类型 API，但不会被包装成 Deployment；
- 定期索引与仓库动态轮询作为一致性回退；SCM Source 会按代码/PR 动态刷新，传统 Job 只回补
  archive 强类型字段可精确重建的 Push/Tag 事件。

已经核验的 endpoint、权限和明确不支持项见 [CNB API 能力矩阵](docs/cnb-api-capabilities.md)，
安全桥接协议见 [Webhook 桥接指南](docs/webhook-bridge.md)。

## 安装

使用仓库内的 Maven Wrapper 构建：

```bash
./mvnw -B -ntp clean verify
```

构建产物为 `target/cnb.hpi`。本插件进入 Jenkins Update Center 之前，手动上传 HPI 不会从
Update Center 自动补齐依赖。容器部署建议先用官方 Plugin Installation Manager 安装 Manifest
列出的直接依赖及其传递依赖，再复制 HPI：

```dockerfile
FROM jenkins/jenkins:2.568.1-jdk21

RUN jenkins-plugin-cli --plugins \
    "workflow-multibranch workflow-step-api branch-api cloudbees-folder \
     credentials-binding credentials git-client git plain-credentials scm-api structs"

COPY --chown=jenkins:jenkins target/cnb.hpi /usr/share/jenkins/ref/plugins/cnb.jpi
```

非容器 Jenkins 可先在 **Manage Jenkins → Plugins** 安装同一组依赖，再到 **Advanced
settings → Deploy Plugin** 上传 `cnb.hpi` 并重启 Controller。版本下限始终以发布 HPI 的
`Plugin-Dependencies` Manifest 字段为准；生产镜像应在首次解析后固定实际安装版本。

项目进入 `jenkinsci` 组织并发布到 Jenkins Update Center 之前，请从本仓库 Release 手动安装
HPI。Release 会同时提供 SHA-256 和 GitHub/Sigstore 构建来源证明，可用以下命令验证：

```bash
gh attestation verify cnb.hpi --repo Zxilly/jenkins-cnb
```

## 凭据与最小权限

建议按职责拆分凭据：

1. 连接测试、Organization Folder 与基础扫描：`account-profile:r`、`account-engage:r`、
   `group-resource:r`、`repo-basic-info:r`、`repo-code:r`、`repo-pr:r` 和 `repo-release:r`；
2. PR 评论、评审以及配置页仓库标签目录：按所用步骤增加 `repo-notes:r`；PR 标签读取使用基础扫描
   已包含的 `repo-pr:r`。启用成员信任或 PR 评论触发时增加 `repo-manage:r`，用于实时查询目标仓库
   成员权限；
3. 结果上报：PR 评论需要 `repo-notes:rw`；Commit annotations 需要 `repo-code:rw`，Tag
   annotations 需要 `repo-release:rw`；批量只读 Commit annotations 至少需要 `repo-code:r`；
4. 显式 PR 写操作：创建、更新、指派、Reviewer、标签和合并需要 `repo-pr:rw`，评论、评审、
   review comment 回复需要 `repo-notes:rw`；
5. Release：查询需要 `repo-release:r`，创建、更新、删除及 Asset 上传/删除需要
   `repo-release:rw`；
6. CNB Build：状态、Stage 和 Runner 日志下载需要 `repo-cnb-trigger:r`，启动/停止需要
   `repo-cnb-trigger:rw`，构建历史需要 `repo-cnb-history:r`；
7. Webhook HMAC：每个仓库使用独立、至少 32 UTF-8 字节的高熵 Secret Text，不能复用 CNB
   Token。

不要授予 `repo-delete:rw`。本插件不会调用仓库删除、转移、成员管理等与 CI 集成无关的高风险
接口。

Secret Text 可以用于 OpenAPI，但不能直接交给 Jenkins Git 插件。私有仓库 checkout 应使用本
插件提供的 **CNB access token** 凭据，或用户名为 `cnb` 的用户名/密码凭据；密码为 CNB Token。
API、checkout 与结果上报可以使用不同凭据。插件只提供 HTTPS Git checkout；CNB 不支持 SSH
checkout，因此本插件不会接受 SSH clone URL 或 SSH 凭据。

## Jenkins 全局配置

打开 **Manage Jenkins → System → CNB**，新增 Server：

- **ID**：稳定且唯一的实例标识，也会出现在 webhook URL 中，例如 `cnb-cool`；
- **Web URL**：例如 `https://cnb.cool`；
- **API URL**：例如 `https://api.cnb.cool`；
- **API credentials**：用于扫描和读取；
- **Result-reporting credentials**：可选的写权限凭据；
- **Repository webhook secrets**：仓库完整路径与 Secret Text 的映射；轮换期可同时保留前一把
  密钥；
- **Build result reporting**：选择 PR 评论、Commit annotation、两者同时或关闭；
- **Event polling**：配置仓库动态回补周期和 webhook 最大时间窗口。

生产支持边界仅包含 HTTPS。私有 CNB 实例访问内网地址需要管理员显式开启，但该选项不会放宽
TLS 要求。私网模式下仍由 Ktor 管理传输，并通过 Jenkins `ProxyConfiguration` 应用 Controller
的代理策略，不会切换为另一套 HTTP transport。

界面中的不安全 HTTP 开关只用于完全隔离的本地测试，不能用于生产。Payload 中携带的
endpoint 永远不会成为插件的出站目标。

JCasC 示例见 [docs/jcasc.yaml](docs/jcasc.yaml)。

## Multibranch Pipeline 与 Organization Folder

在 Multibranch Pipeline 中添加 **CNB** Branch Source，选择 Server 和凭据，填写完整仓库路径，
例如 `group/subgroup/repository`。随后按需配置：

- 分支发现以及受保护/锁定分支过滤；
- Tag 发现；
- 同仓 PR 的 HEAD/MERGE 策略；
- Fork PR 的 HEAD/MERGE、Draft、源/目标分支、标签过滤和信任策略；
- 可选的 **Build pull requests from authorized CNB comments** Trait：使用 RE2/J 完整匹配评论，且
  评论者在目标仓库的实时角色必须达到 Reporter、Developer、Master 或 Owner 中配置的最低值，
  默认为 Developer；
- 自动上报 context；或按 Branch Source 跳过自动 queued/running/final 上报。

Organization Folder 使用 **CNB Organization**，可递归发现子组织并默认排除归档仓库；管理员可
显式启用归档仓库发现。Secret 仓库无法 clone，因此不会进入可构建来源。

Fork PR 默认不可信。除非配置的 authority 明确信任来源，否则 Jenkinsfile 从目标分支读取，
从而避免 Fork 代码直接接触 Jenkins 凭据。

PR 标签字段使用当前 Branch Source 的 API 凭据和 Item 上下文查询仓库标签；查询结果按
Server、仓库、凭据和 Item 隔离缓存。保存时的未知标签或 CNB 暂不可用只产生警告，实际索引仍实时
读取 PR 标签并 fail closed。Git changelog 会把明确的 `PR #123`、`pull request #123` 和完整
40/64 位 Commit SHA 链接到正确的目标仓库或 Fork 源仓库，不会把普通 `#123` 文本误判成 PR。

## Webhook 与传统 Job

CNB 没有可由 Jenkins 自动创建的仓库 Webhook。请按
[Webhook 桥接指南](docs/webhook-bridge.md) 在 `.cnb.yml` 中使用固定版本的官方
`cnbcool/webhook` 镜像，并将请求发送到：

```text
https://<jenkins>/cnb-webhook/<server-id>/
```

传统 Job 的 **Build on CNB code or pull request events** Trigger 可按事件、分支/Tag、PR 源分支、
PR 目标分支、Draft/WIP、必需标签和排除标签过滤。默认只启用可信的 `push` 与 `tag_push`；不可信
PR/评论事件必须显式开启。评论触发还必须配置非空 RE2/J 表达式和最低目标仓库成员角色，默认完全
关闭。签名通过后，插件仍会从 CNB API 重新读取分支、Tag、PR revision、标签、评论正文和评论者
权限；评论 ID、作者或正文被篡改、评论已删除、权限不足或 API 无法授权时均不会入队。多个传统
Job 会针对同一个实时快照分别执行自己的策略，而不是复用第一个 Job 的策略。PR 更新时可分别选择
取消同一 PR/SHA 身份下已过期的排队构建和运行中构建；两个选项都默认关闭。

“在分支 push 时构建开放 PR”有 `never`、`source` 和 `both` 三种模式。`source` 接受 CNB
`pull_request.target` 事件；`both` 还会在目标分支 push 时列出开放 PR，并逐个回读 PR、源/目标
branch、标签和提交。Classic Job 只有在其 GitSCM 已配置一个与该 PR 源仓库等价的 remote 时才会
入队，revision action 使用 GitSCM 的精确 URI；Fork remote 不存在时安全跳过，绝不退回默认分支。
Multibranch 则继续通过对应 SCM Source 和 item-scoped 凭据发现 revision。

与参考 GitLab Trigger 的行为对照：

| GitLab Trigger 能力 | CNB Trigger 对齐行为 |
| --- | --- |
| Enable `[ci-skip]` | 默认启用；对 CNB API 回读的权威头提交消息（PR 还包括实时描述）识别 `[ci-skip]`、`[ci skip]`、`[skip ci]`，不区分大小写 |
| Build only if new commits were pushed to Merge Request | 可选；仅当同一 Job、同一 PR 最近一次持久化 `CnbQueueAction` 的 source SHA 发生变化时触发，默认关闭 |
| Trigger open Merge Requests on push | 支持源分支与源+目标分支两种模式；所有 API 读取在首次队列修改前完成，Classic checkout 额外验证 Git remote |
| Set build description | 默认开启，仅填充空的构建描述；用户或 Pipeline 已有描述不会被覆盖 |
| Project labels and Merge Request label filters | Classic 与 Multibranch 都使用有界仓库标签目录提供联想/警告，运行时只信任实时 PR 标签 |

CI skip 不信任桥接正文；提交不存在、无权读取或 API 返回的 SHA 与已校验 revision 不一致时 fail closed。
“仅新 source SHA”状态复用 Jenkins 排队项与 Run 上已有的持久 Action，因此控制器重启后仍有效，且队列拒绝
投递时不会提前消费 revision。

GitLab 的 “Labels that launch a build if they are added” 依赖 webhook 同时提供标签变更前后的集合。
CNB 当前公开 OpenAPI、仓库事件归档和官方 webhook bridge 只提供当前标签，没有可验证的 previous
集合；插件不会凭内存猜测“刚新增”而在重启或漏事件后误触发。这个差异及 GitHub/GitLab 参考审计见
[参考插件能力审计](docs/reference-plugin-audit.md)。

构建会获得持久化 Cause，并注入以下常用变量：

```text
CNB_SERVER_ID
CNB_EVENT
CNB_REPOSITORY
CNB_BRANCH
CNB_COMMIT
CNB_PULL_REQUEST_IID
CNB_PULL_REQUEST_SOURCE_BRANCH
CNB_PULL_REQUEST_SOURCE_SHA
CNB_PULL_REQUEST_TARGET_SHA
CNB_PULL_REQUEST_ACTION
```

## Pipeline 与 Freestyle 操作

插件提供构建元数据上报，以及由用户显式调用的 PR、Build、Release 操作。返回值来自强类型领域
模型，只在 Pipeline 边界转换为 CPS 可安全持久化的基础值、List 和 Map。所有可选参数和生成示例
以安装后 Jenkins 内置 **Pipeline Syntax** 页面为准。下列 API steps 都可选传入 `serverId`、
`repository`、`pullRequestNumber`、`sha` 和 `credentialsId`；省略时从当前构建上下文解析。下面只列
各操作自己的参数。

通用与代码查询 symbols：

- `withCredentials([cnbToken(credentialsId: '...', variable: 'CNB_TOKEN')])`：以掩码环境变量绑定
  CNB Token；
- `cnbBuildMetadata`：写入/更新 Jenkins 构建元数据；
- `cnbCommitStatuses`：只读查询 CNB 原生 Commit Status；
- `cnbCommit`、`cnbCommits`、`cnbCompareCommits`：查询提交、历史和差异；
- `cnbCommitAnnotations`：以 `commitHashes` 批量查询 1 到 20 个完整 SHA，可选最多 5 个 `keys`，
  返回 `[{commitHash, annotations: [{key, value}]}]`。

PR symbols：

- `cnbPullRequests`：可选 `state`（`open`、`closed`、`all`）；`cnbPullRequest`：读取上下文中的
  当前/指定 PR；
- `cnbCreatePullRequest`、`cnbUpdatePullRequest`、`cnbMergePullRequest`：创建、更新/关闭及以
  merge、squash 或 rebase 方式合并；创建必填 `targetBranch`、`sourceBranch`、`title`，更新至少
  显式传一个 `title`、`body`、`state`，合并必填 `method`；
- `cnbPullRequestAssignees`、`cnbAddPullRequestAssignees`、
  `cnbRemovePullRequestAssignees`：查询、添加和删除 Assignee；后两者传 `usernames`，删除还必填
  `confirm`；
- `cnbAddPullRequestReviewers`、`cnbRemovePullRequestReviewers`：以 `usernames` 添加和删除
  Reviewer；删除还必填 `confirm`；
- `cnbPullRequestComments`、`cnbPullRequestCommentById`、`cnbPullRequestComment`、
  `cnbUpdatePullRequestComment`：列出、按 `commentId` 读取、以 `comment` 创建或以
  `commentId`/`comment` 更新 PR 评论；
- `cnbPullRequestLabelExists`、`cnbPullRequestLabels`：查询标签，并以 `list`、`add`、`replace`、
  `remove` 或 `clear` 模式修改标签；后者必填 `mode` 和 `labels`，破坏性模式还需要 `confirm`；
- `cnbPullRequestCommits`、`cnbPullRequestFiles`、`cnbPullRequestStatuses`：查询 PR 提交、文件和
  只读原生状态；
- `cnbPullRequestReviews`、`cnbPullRequestReviewComments`、`cnbReviewPullRequest`、
  `cnbReplyPullRequestReviewComment`：查询/提交评审并回复 review comment；分别使用 `reviewId`、
  `action`，或 `reviewId`/`commentId`/`body`。

CNB Build symbols：

- `cnbStartBuild`：必填 `event`（`api_trigger` 或 `api_trigger_*`），可选分支/Tag、标题、配置、
  同步、环境变量和 NPC 参数；`cnbBuildStatus`、`cnbStopBuild` 必填 `sn`；
- `cnbBuildHistory`：使用可选的时间、事件、ref、状态、构建号和用户过滤条件；
  `cnbBuildStage` 必填 `sn`、`pipelineId`、`stageId`；
- `cnbDownloadBuildRunnerLog`：必填 `pipelineId`、workspace `path`，可选 `overwrite`、`maxBytes`；
  有大小上限地安全下载，不把日志正文作为 Pipeline 返回值或写入 Jenkins 日志。

Release 与 Asset symbols：

- `cnbReleases`、`cnbLatestRelease`、`cnbRelease`、`cnbReleaseByTag`：列出 Release，并按 latest、
  `releaseId` 或 `tag` 查询；
- `cnbCreateRelease`：必填 `tagName`、`targetCommitish`；`cnbUpdateRelease` 必填 `releaseId` 且至少
  显式传一个可变字段；两者可设置 `name`、`body`、`draft`、`prerelease`、`makeLatest`；
- `cnbDeleteRelease`：必填 `target`、`confirm`，按 Tag 删除时设置 `byTag`；
- `cnbReleaseAsset` 必填 `releaseId`、`assetId`；`cnbReleaseAssetHead` 必填 `tag`、`assetName`；
  `cnbDeleteReleaseAsset` 再必填精确匹配 `assetId` 的 `confirm`；
- `cnbUploadReleaseAsset`、`cnbDownloadReleaseAsset`：在 CNB 与 Jenkins workspace 之间流式传输
  Asset，不把预签名 URL 暴露给 Pipeline；上传必填 `releaseId`、`path`，下载必填 `tag`、
  `assetName`、`path`，并分别提供 `assetName`/`overwrite`/`ttlDays` 或
  `share`/`overwrite`/`maxBytes` 可选项。

写操作不会由 webhook 自动执行，避免 Jenkins 与 CNB 之间形成递归构建。Token、构建环境中的
敏感变量以及 CNB 响应体不会写入日志。创建 PR 前会确认源分支仍匹配当前构建 SHA；对现有 PR 的
mutation 会实时重新读取完整 SHA，拒绝陈旧 revision。删除 Assignee/Reviewer、replace/remove/
clear 标签和关闭 PR 要求确认值精确匹配 PR number；Release/Asset 删除同样要求确认值精确匹配
目标 ID 或 Tag。workspace 路径必须为不含父目录、盘符或反斜杠的相对路径，下载先写随机临时
文件，长度校验成功后才原子发布。

流式资源限制不是业务文件大小偏好，而是 Controller/Agent 的安全预算：CNB 响应和对象存储内容均
视为不可信，若无分页条目数、累计字节、单文件大小、并发、排队等待与超时上限，单个超大或永不结束
的响应就可能耗尽 Controller heap、Agent 磁盘、HTTP 连接或 Jenkins 执行线程。上传/下载因此边读边
计数，失败只留下可清理的临时文件，不把整个 Asset 或日志物化到内存、build.xml 或 Pipeline 返回值。

Freestyle 可使用 **Report build metadata to CNB**，并可配置显式 PR 操作 Publisher。PR 操作的
**Only run after a successful build** 默认开启；不稳定、失败、中止或未构建结果会跳过 CNB 请求。
关闭后会在非成功构建上仍执行，但校验或 CNB API 失败仍会使构建失败。跨仓 PR 中，
`repository` 表示拥有 PR 的目标仓库，`commitRepository` 表示拥有源 SHA 的 Fork 仓库。

## 结果上报

Branch Source 构建会按 Server 配置自动上报 queued、running 和最终状态。传统 Job 可使用
Publisher，Pipeline 可调用 `cnbBuildMetadata`。`cnbSkipReporting` Trait 只关闭生命周期监听器的
自动上报，不会禁止显式 Pipeline/Publisher 调用；`cnbReportingContext` Trait 提供自动上报的
默认 context，显式参数优先。上报具备以下性质：

- 每个 context 独立，允许并行 stage 使用不同名称；
- PR 评论带不可见幂等标记，重试时更新而不是重复创建；
- CNB annotation key 只允许 ASCII 字母、数字、下划线和连字符；插件使用
  `jenkins_<context>-<hash>_<field>` 命名空间，只修改自己的 key，不覆盖其他系统的数据。字符约束见
  [CNB annotations 官方说明](https://cnb.cool/cnb/plugins/cnbcool/annotations)；
- 状态持久化在 `build.xml`，Jenkins 重启后继续对账；
- 网络失败采用有上限的退避重试，不修改 Jenkins 原始构建结果。

由于 CNB 没有原生状态写接口，这些 metadata 不能替代 CNB 保护分支中的 required status check。

## 运维建议

- Webhook 密钥轮换：先配置新 current 和旧 previous，等待一个事件窗口后删除 previous；
- 保持仓库动态轮询开启；已完成 UTC 小时的 cursor 和事件 ID 会持久化，Controller 恢复后最多每轮
  回补 24 小时；轮询状态按 Jenkins 授权上下文隔离，传统 Job 的回补范围为 Push/Tag；
- 保持 Organization Folder 定期 reconcile 开启，默认每六小时进行带抖动的完整扫描；
- `401` 通常表示 Token 失效，`403` 通常表示用户角色或 scope 不足；
- 插件只对网络错误、`429`、`500`、`502`、`503` 和 `504` 重试幂等请求；
- 定期检查 Jenkins 的 CNB webhook、轮询与上报健康状态页面。

## 开发与测试

```bash
./mvnw -B -ntp clean verify
./mvnw hpi:run
```

项目使用 Jenkins LTS 2.541.3、Plugin Parent `6.2211.v27f680c93c53`、Jenkins BOM
`6699.v4f03a_ff2f9c2`、Kotlin 2.4.10、Ktor Client 3.5.1（Apache5 engine、ContentNegotiation）、
kotlinx.serialization 1.11.0、kotlinx-io 0.9.1、RE2/J 1.8、Maven 3.9.16（Wrapper 3.3.4）、ktlint 1.8.0 和
JaCoCo 0.8.15，生成 Java 17 bytecode，并在 Java 17/21/25 与 Windows/Linux 上运行 CI。每次
`verify` 都会执行单元测试和 Jenkins Test Harness 集成测试，
包括 JCasC、Jenkins 重启持久化、Webhook/队列、SCM Source、Pipeline 与安全失败路径，生成
`target/site/jacoco`，并强制聚合行覆盖率不低于 86%（高于项目要求的 85%）。GitHub Actions
还在 Ubuntu Java 17/21/25、Windows Java 17 上分别构建，并运行 Jenkins Security Scan。

测试类默认在相互隔离的 JVM 中以 `0.45C` 并行执行，CI 则覆盖为更激进的 `1C`，即每个可用核心
运行一个测试 fork；本地内存受限或需要复现顺序相关问题时，可使用
`./mvnw -DforkCount=1 test` 回退为串行。Jenkins Test Harness 依赖全局状态，因此不启用 JUnit
方法级线程并行。最新 Jenkins 注解处理器要求 Maven/KAPT 运行在 Java 21+；CI 通过 Maven
Toolchains 让完整测试套件分别运行在 Java 17/21/25，并继续生成 Java 17 bytecode。

发布前还必须在全新的 Docker Jenkins LTS 上安装本次 HPI，针对真实 CNB 测试仓库执行烟测：
验证全局配置与健康页、HTTPS checkout、传统 Job 与 Multibranch 构建、Smee 到本地 Jenkins 的
签名 Webhook、错误签名拒绝、防重放，以及 PR/Build/Release/Asset 的代表性读写与清理流程。
烟测凭据和运行证据只存放在被 `.gitignore` 排除的本地目录，不能提交、复制到日志或写入文档。

当前稳定 CodeQL 尚不能解析 Kotlin 2.4.10。安全扫描会对同一份生产源码使用仅分析的 Kotlin
2.4.0 兼容 profile；正常构建、测试和 Release 始终使用 2.4.10。待稳定 CodeQL 包含上游 Kotlin
2.4.10 支持后将移除该兼容 profile。

贡献规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，架构说明见
[docs/architecture.md](docs/architecture.md)。

## 安全问题

请不要在公开 Issue 中报告疑似漏洞。处理方式见 [SECURITY.md](SECURITY.md)。
