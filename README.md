# CNB Plugin

[![Build](https://github.com/Zxilly/jenkins-cnb/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/Zxilly/jenkins-cnb/actions/workflows/build.yml)
[![Jenkins Security Scan](https://github.com/Zxilly/jenkins-cnb/actions/workflows/jenkins-security-scan.yml/badge.svg?branch=master)](https://github.com/Zxilly/jenkins-cnb/actions/workflows/jenkins-security-scan.yml)
[![License](https://img.shields.io/github/license/Zxilly/jenkins-cnb.svg)](LICENSE)

这个插件将 Jenkins 与 [CNB](https://cnb.cool) 仓库连接起来。它可以根据 CNB 代码和 Pull Request
事件触发构建，发现 Multibranch 和 Organization Folder 项目，并将构建结果上报到 CNB。

插件短名称为 `cnb`。

## 目录

- [功能](#功能)
- [系统要求](#系统要求)
- [安装](#安装)
- [凭据与权限](#凭据与权限)
- [全局配置](#全局配置)
- [Jenkins Job 配置](#jenkins-job-配置)
- [Webhook](#webhook)
- [构建环境变量](#构建环境变量)
- [Pipeline](#pipeline)
- [结果上报](#结果上报)
- [已知限制](#已知限制)
- [故障排查](#故障排查)
- [支持与贡献](#支持与贡献)

## 功能

- 连接 `cnb.cool` 或兼容 CNB OpenAPI 的私有实例，并支持多个 Server 配置。
- 支持 Freestyle、Pipeline、Multibranch Pipeline 和 Organization Folder。
- 发现分支、Tag、同仓 Pull Request 和 Fork Pull Request。
- 支持 Pull Request HEAD 与 MERGE 两种 checkout 策略。
- 在 Multibranch Pipeline 和 Organization Folder 中显示 CNB namespace 头像。
- 将 Jenkins changelog 中明确的 CNB Pull Request 和 Commit 转为可点击链接。
- 接收 Push、分支、Tag、Pull Request、评审和评论事件。
- 按分支、Tag、Pull Request、Draft、标签和评论者角色过滤构建。
- 在 Pull Request 评论以及 Commit/Tag annotations 中上报构建状态。
- 列出、读取和上传 CNB Badge。
- 提供 Pull Request、CNB Build、Release 和 Release Asset Pipeline steps。
- 支持 JCasC、事件轮询、凭据轮换和 Jenkins 重启恢复。

## 系统要求

- Jenkins `2.541.3` 或更高版本。
- 支持 Java 17、21 和 25；Jenkins Controller 与执行插件 workspace steps 的 Agent 使用相同支持范围。
- 生产环境只支持 HTTPS CNB Web/API、Webhook 和 Git checkout 地址。
- Git checkout 使用 HTTPS。CNB 不支持 SSH checkout，因此插件不接受 SSH clone URL 或 SSH 凭据。

最低依赖版本以发布 HPI 的 `Plugin-Dependencies` Manifest 字段为准。

## 安装

### 从 GitHub Release 安装

1. 从 [GitHub Releases](https://github.com/Zxilly/jenkins-cnb/releases) 下载 `cnb.hpi` 和校验文件。
2. 在 **Manage Jenkins > Plugins > Advanced settings** 中上传 `cnb.hpi`。
3. 重启 Jenkins。

手动上传 HPI 时，Jenkins 不会自动从 Update Center 安装依赖。请先安装以下插件：

```text
workflow-multibranch workflow-step-api branch-api cloudbees-folder
credentials-binding credentials git-client git plain-credentials scm-api structs
```

发布版本提供 GitHub 构建来源证明时，可以使用以下命令验证：

```bash
gh attestation verify cnb.hpi --repo Zxilly/jenkins-cnb
```

### Docker

```dockerfile
FROM jenkins/jenkins:2.568.1-jdk21

RUN jenkins-plugin-cli --plugins \
    "workflow-multibranch workflow-step-api branch-api cloudbees-folder \
     credentials-binding credentials git-client git plain-credentials scm-api structs"

COPY --chown=jenkins:jenkins cnb.hpi /usr/share/jenkins/ref/plugins/cnb.jpi
```

生产镜像应固定 Jenkins、依赖插件和基础镜像 digest。

## 凭据与权限

### 凭据类型

插件支持以下 Jenkins Credentials：

- **CNB access token**：同时用于 CNB API 和 HTTPS Git checkout，Git 用户名固定为 `cnb`。
- **Secret Text**：可用于 CNB API，不能直接用于 Git checkout。
- **Username with password**：可用于 CNB API；用于 checkout 时用户名必须为 `cnb`，密码填写 CNB Token。
- **Secret Text webhook key**：每个仓库独立配置，至少 32 个 UTF-8 字节。

API、checkout 和结果上报可以使用不同凭据。Webhook HMAC 密钥不能复用 CNB Token。

### 最小权限

| 用途 | CNB Token scope |
| --- | --- |
| 基础扫描和 Organization Folder | `account-profile:r account-engage:r group-resource:r repo-basic-info:r repo-code:r repo-pr:r repo-release:r` |
| Pull Request 评论和评审读取 | `repo-notes:r` |
| 成员信任和评论触发 | `repo-manage:r` |
| Pull Request 评论上报 | `repo-notes:rw` |
| Commit annotation 上报 | `repo-code:rw` |
| Tag annotation 上报 | `repo-release:rw` |
| Badge 读取 | `repo-commit-status:r` |
| Badge 上传 | `repo-commit-status:rw` |
| Pull Request 写操作 | `repo-pr:rw`；评论、评审和回复还需要 `repo-notes:rw` |
| Release 读取 | `repo-release:r` |
| Release 写入 | `repo-release:rw` |
| CNB Build 状态、Stage 和日志 | `repo-cnb-trigger:r` |
| 启动和停止 CNB Build | `repo-cnb-trigger:rw` |
| CNB Build 历史 | `repo-cnb-history:r` |

不要授予 `repo-delete:rw`。仅按实际启用的功能授予上表列出的最小 scope。

## 全局配置

打开 **Manage Jenkins > System > CNB**，添加一个 CNB Server。

| 字段 | 说明 |
| --- | --- |
| **ID** | 稳定且唯一的 Server ID，例如 `cnb-cool`；它也会出现在 Webhook URL 中。 |
| **Name** | Jenkins 中显示的名称。 |
| **Web URL** | CNB Web 地址，例如 `https://cnb.cool`。 |
| **API URL** | CNB API 地址，例如 `https://api.cnb.cool`。 |
| **API credentials** | 用于扫描和读取 CNB API。 |
| **Result-reporting credentials** | 可选，用于评论和 annotation 写入；留空时回退到 API credentials。 |
| **Repository webhook secrets** | 仓库完整路径与 Secret Text 的映射。 |
| **Build result reporting** | 选择 Pull Request 评论、Commit/Tag annotation、两者同时或关闭。 |
| **Event polling** | 配置仓库事件轮询和 Webhook 时间窗口。 |
| **Timeouts** | 配置连接与请求超时。 |

点击 **Test scan/API credential** 验证 URL、凭据和用户身份。

默认上报模式为同时写入 Pull Request 评论和 Commit/Tag annotation。API credentials 只有读取权限时，
请配置独立的 Result-reporting credentials，或关闭自动上报。

Webhook 密钥轮换时，将新密钥设为 current，并在 previous 中保留旧密钥。等待一个 Webhook 时间窗口后，
再删除 previous。

私有 CNB 实例访问内网地址时，需要管理员显式允许私网访问。不安全 HTTP 选项只用于隔离的本地测试。

JCasC 示例见 [docs/jcasc.yaml](docs/jcasc.yaml)。

## Jenkins Job 配置

### Multibranch Pipeline

1. 创建 **Multibranch Pipeline**。
2. 在 **Branch Sources** 中添加 **CNB repository**。
3. 选择 Server、API credentials 和 checkout credentials。
4. 填写完整仓库路径，例如 `group/subgroup/repository`。
5. 配置需要的发现与过滤 Traits，然后运行索引。

可用 traits 包括：

- 分支发现以及受保护/锁定分支过滤。
- Tag 发现。
- 同仓 Pull Request HEAD/MERGE 发现。
- Fork Pull Request HEAD/MERGE、Draft、分支、标签和信任过滤。
- 基于授权 CNB 评论触发 Pull Request 构建。
- 自动结果上报 context，或关闭 Branch Source 自动上报。

Fork Pull Request 默认不可信。除非显式配置其他 authority，Jenkinsfile 从目标分支读取，Fork 只提供
待构建源码。

### Organization Folder

1. 创建 **Organization Folder**。
2. 添加 **CNB namespace**。
3. 选择 Server 和凭据，并填写 CNB 组织路径。
4. 配置仓库过滤和 discovery traits。

Navigator 可以递归发现子组织。归档仓库默认排除，Secret 仓库不会作为可 checkout 来源。

### Freestyle 和普通 Pipeline Job

在 **Source Code Management > Git** 中填写 CNB HTTPS clone URL，并选择用户名为 `cnb` 的凭据。

启用 **Build on CNB code or pull request events** 后，可以配置：

- Push、Tag、分支和 Pull Request 事件。
- 分支、Tag、Pull Request 源分支和目标分支过滤。
- Draft/WIP、必需标签和排除标签。
- `[ci skip]`、`[ci-skip]` 和 `[skip ci]`。
- 仅在 Pull Request source SHA 变化时构建。
- 源分支或源+目标分支更新时构建开放 Pull Request。
- Pull Request 更新时取消过期的排队构建或运行中构建。
- 使用 RE2/J 表达式和目标仓库成员角色限制评论触发。
- 使用 CNB Cause 填充空的构建描述；该选项默认开启。

默认只启用 `push` 和 `tag_push`。Pull Request 和评论事件需要显式开启。

Freestyle Job 还可以添加以下 Post-build Actions：

- **Report build metadata to CNB**
- **Perform a CNB pull request action**

Pull Request Action 默认只在成功构建后执行。关闭此限制前，请确认失败构建也应修改 CNB；破坏性操作
仍需要显式确认值。

## Webhook

CNB 当前没有供 Jenkins 自动创建仓库 Webhook 的 API。请在可信的 `.cnb.yml` 中使用固定版本的
`cnbcool/webhook:v1.0.2`，并按照 [Webhook 桥接指南](docs/webhook-bridge.md) 配置事件转发。

Jenkins 接收地址为：

```text
https://<jenkins>/cnb-webhook/<server-id>/
```

配置步骤：

1. 在 Jenkins 中创建至少 32 字节的 Secret Text。
2. 在 CNB Server 的 **Repository webhook secrets** 中绑定完整仓库路径。
3. 将相同值保存为 CNB 受保护仓库密钥 `JENKINS_CNB_WEBHOOK_SECRET`。
4. 在 `.cnb.yml` 中只引用 `${JENKINS_CNB_WEBHOOK_SECRET}`，不要提交明文密钥。
5. 确保外部 CNB 可以通过 HTTPS 访问 Jenkins。

Webhook 只接受带 `X-CNB-Signature` 的 JSON POST。每个仓库使用独立 HMAC 密钥，请求体最大为
1 MiB；超限请求返回 `413`。

插件会校验时间窗口和 delivery ID，并在调度前从 CNB API 确认仓库 revision。保持事件轮询开启，
可以刷新 SCM Source，并为 Classic Job 回补 Push/Tag。Pull Request、评审和评论事件仍必须通过 Webhook。

## 构建环境变量

Webhook 触发的 Classic Job 会获得 `CNB_*` 环境变量。常用变量包括：

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

Pull Request、评审和评论事件还会提供：

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

不适用于当前事件的变量为空字符串。

## Pipeline

安装后打开 **Pipeline Syntax**，选择 CNB step 查看当前版本的完整参数和返回值。

除 `cnbBuildMetadata` 外，CNB API steps 都支持以下可选上下文参数：

```text
serverId repository pullRequestNumber sha credentialsId
```

`serverId`、`repository`、`pullRequestNumber` 和 `sha` 可以从 Multibranch、Webhook Cause 或构建环境
解析。`credentialsId` 不从环境变量读取；省略时使用 item-scoped SCM 凭据或 Server API credentials。

### Step 索引

- 通用与提交：`cnbBuildMetadata`、`cnbCommit`、`cnbCommits`、`cnbCompareCommits`、
  `cnbCommitAnnotations`、`cnbCommitStatuses`。
- Badge：`cnbBadges`、`cnbBadge`、`cnbUploadBadge`。
- Pull Request 查询和修改：`cnbPullRequests`、`cnbPullRequest`、`cnbCreatePullRequest`、
  `cnbUpdatePullRequest`、`cnbMergePullRequest`、`cnbPullRequestAssignees`、
  `cnbAddPullRequestAssignees`、`cnbRemovePullRequestAssignees`、`cnbAddPullRequestReviewers`、
  `cnbRemovePullRequestReviewers`。
- Pull Request 评论、标签和评审：`cnbPullRequestComments`、`cnbPullRequestCommentById`、
  `cnbPullRequestComment`、`cnbUpdatePullRequestComment`、`cnbPullRequestLabelExists`、
  `cnbPullRequestLabels`、`cnbPullRequestCommits`、`cnbPullRequestFiles`、
  `cnbPullRequestStatuses`、`cnbPullRequestReviews`、`cnbPullRequestReviewComments`、
  `cnbReviewPullRequest`、`cnbReplyPullRequestReviewComment`。
- CNB Build：`cnbStartBuild`、`cnbBuildStatus`、`cnbStopBuild`、`cnbBuildHistory`、
  `cnbBuildStage`、`cnbDownloadBuildRunnerLog`。
- Release 和 Asset：`cnbReleases`、`cnbLatestRelease`、`cnbRelease`、`cnbReleaseByTag`、
  `cnbReleaseAsset`、`cnbReleaseAssetHead`、`cnbCreateRelease`、`cnbUpdateRelease`、
  `cnbDeleteRelease`、`cnbDeleteReleaseAsset`、`cnbUploadReleaseAsset`、
  `cnbDownloadReleaseAsset`。

### Badge 示例

在 checkout 后调用上传步骤，确保 `env.GIT_COMMIT` 包含完整 Commit SHA。

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

Badge 用于 README 和视觉展示，不是 CNB Commit Status 或合并门禁。CNB 当前文档化的上传 key 为
`security/tca`，最终允许的 key 由 CNB 服务端决定。

### Pull Request 示例

以下示例用于 Pull Request 构建；普通分支构建需要显式传入 `repository`、`pullRequestNumber` 和 `sha`。

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

### CNB Token 绑定

```groovy
withCredentials([cnbToken(credentialsId: 'cnb-api', variable: 'CNB_TOKEN')]) {
  sh 'curl --fail --header "Authorization: Bearer $CNB_TOKEN" https://api.cnb.cool/user'
}
```

Jenkins 会掩码 `CNB_TOKEN`。不要把 Token 拼入 URL、构建描述或返回值。

删除 Release/Asset、移除 Assignee/Reviewer、清空、移除或替换标签以及关闭 Pull Request 等操作需要
`confirm` 参数。确认值必须与目标 ID、Tag 或 Pull Request number 精确匹配。

workspace 上传和下载路径必须是相对路径，不能包含父目录、盘符或反斜杠。Release Asset 上传固定限制为
512 MiB；Release 下载和 Runner log 下载可以用 `maxBytes` 进一步收紧。

## 结果上报

Multibranch 构建可以自动上报 queued、running 和最终状态。Classic Job 使用
**Report build metadata to CNB**，Pipeline 使用 `cnbBuildMetadata`。

上报目标由 CNB Server 的 **Build result reporting** 决定：

- Pull Request 评论。
- Commit/Tag annotation，具体目标由当前构建上下文决定。
- Pull Request 评论和 Commit/Tag annotation 同时上报。
- 关闭自动上报。

结果凭据按以下顺序选择：step 或 Job 显式凭据、Result-reporting credentials、API credentials。

`cnbSkipReporting` trait 可以关闭某个 Branch Source 的自动上报；`cnbReportingContext` trait
可以设置默认 context。显式 Pipeline step 和 Freestyle Publisher 不受 `cnbSkipReporting` 影响。

CNB 没有供 Jenkins 写入的原生 Commit Status API。插件上报的评论和 annotations 不能替代 CNB
保护分支中的 required status check。

## 已知限制

- Jenkins 无法通过 CNB API 自动创建或删除 Webhook，必须使用 `.cnb.yml` bridge。
- CNB 原生 Commit Status 只能读取，不能由 Jenkins 写入。
- Badge 是视觉展示，不是 Commit Status 或 required check。
- CNB 没有供外部 CI 使用的 Deployment API。
- CNB Webhook 不提供可信的标签变更前集合，因此不支持“仅在某标签刚添加时触发”。
- Git checkout 只支持 HTTPS，不支持 SSH。

## 故障排查

### Webhook 没有触发构建

- 检查 URL 中的 Server ID 是否与 Jenkins 全局配置一致。
- 检查仓库路径是否绑定了正确的 Secret Text。
- 检查 `.cnb.yml` 是否使用固定的 `cnbcool/webhook:v1.0.2`。
- 确认事件已在 Job trigger 中启用，并通过分支、标签和 Draft 过滤。
- `413` 表示 Webhook 请求体超过 1 MiB。
- 打开 **Manage Jenkins > CNB operational health** 查看最近的 Webhook 结果。

### CNB API 返回错误

- `401` 通常表示 Token 无效或过期。
- `403` 通常表示用户角色或 Token scope 不足。
- `429` 或 `5xx` 可能是 CNB 限流或临时服务故障。

### Git checkout 失败

- 使用 HTTPS clone URL。
- 使用 **CNB access token**，或用户名为 `cnb` 的 Username with password。
- 不要选择 Secret Text 作为 checkout credential。
- 确认 Token 具有 `repo-code:r` 并能访问目标仓库。

需要调试日志时，在 **Manage Jenkins > System Log** 中为
`dev.zxilly.jenkins.cnb` 添加 Logger。日志中不要粘贴 Token、Webhook payload 或 HMAC 密钥。

## 支持与贡献

这个插件由社区维护，不代表 CNB 或 Jenkins 官方提供商业支持。

- 使用问题和功能建议：[GitHub Issues](https://github.com/Zxilly/jenkins-cnb/issues)
- 安全问题：[SECURITY.md](SECURITY.md)
- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 版本记录：[CHANGELOG.md](CHANGELOG.md)
- 许可证：[MIT](LICENSE)

从源码构建需要 JDK 21 或 25。构建环境准备见 [CONTRIBUTING.md](CONTRIBUTING.md)。

本地开发：

```bash
./mvnw -B -ntp clean verify
./mvnw hpi:run
```
