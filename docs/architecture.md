# 架构说明

插件按职责拆成窄边界模块：

- `config`、`credentials`：多 CNB Server、凭据引用和 JCasC；
- `security`：endpoint、仓库路径、Git 对象 ID 和入站请求策略；
- `api`：基于 Ktor Client 3.5.1 与 ContentNegotiation 的强类型 CNB OpenAPI 客户端；
- `scm`：SCM Source/Navigator、head、revision、Trait、probe 和 GitSCM 装配；
- `webhook`、`trigger`：签名事件规范化、防重放、SCM event 和传统 Job Cause；
- `status`、`pipeline`、`publisher`：构建 metadata 生命周期与显式用户操作；
- `health`、`ui`：仅管理员可见的运行状态与上下文 CNB 链接。

## 出站网络边界

API 客户端只接受插件自己构造的相对路径。Server endpoint 在配置保存和每次请求时都会校验；
payload 和 API response 中的 endpoint 不会成为新的请求 origin。每个 Server 独立维护并发限制、
超时、退避、限流和 circuit 状态。

Ktor 统一负责 HTTP 执行、`HttpTimeout`、响应处理、流式 body 与 `HttpClient` 生命周期。
Apache5 仅作为 Ktor 官方 JVM engine，不是第二套并列 transport。选用它是为了在实际 socket
建连的 DNS resolver 中校验并返回同一组地址，消除先校验、后重新解析的窗口。

公网模式要求 resolver 返回的每个地址都是 public unicast。私网模式显式放宽该地址策略，
但仍由 Ktor Apache5 engine 发送请求，并通过 Jenkins `ProxyConfiguration` 应用 Controller 的代理
策略；公网与私网模式共用同一 Ktor transport 和 engine 生命周期。

Ktor 自动重定向关闭。仓库动态、Release Asset 下载和 signed upload 逐跳重新校验 URI、
scheme、host、userinfo、fragment、地址类别和长度；离开 CNB API origin 后不再发送 Bearer
Token。Release 上传确认 URL 还必须与配置的 API origin、仓库、Release ID 和确认路径精确一致。

## 强类型 JSON 边界

Ktor ContentNegotiation 注册共享的 `kotlinx.serialization` `Json` 策略。常规 2xx 单对象响应通过
完整 `TypeInfo` 进入 Ktor converter 和私有 Wire DTO；分页、数组/Envelope 联合响应、错误体与下载
仍显式保留原始字节，以便执行跨页字节计数、受限错误处理和流式写入。请求使用同一组生成的
serializer 预编码，并在全部重试结束后清零缓冲。两条路径共享同一个严格 `Json` 实例，均不引入
Jackson 或反射映射。Webhook 的精确原始 body 也使用同一 serializer 策略解码，但不经过出站
HTTP 客户端。

声明为 JSON 的单对象成功响应除 `204`、`205` 或明确的零长度外必须包含有效 JSON；未知长度的空
chunked/HTTP/2 body 会 fail closed，而不会被静默当作一个缺失对象。分页和下载仍按原始流语义处理。

未知字段可忽略以允许 CNB 向后兼容地增加字段；必需字段、枚举、完整 SHA、资源 ID、路径、URL、
时间、计数和标量形状保持严格。未知枚举和冲突字段 fail closed。

JSON 对象直接通过显式 serializer 进入 typed DTO；`JsonNode`、`ObjectMapper`、反射对象绑定以及
`Map<String, Any?>` 不会穿过传输边界。Pipeline 需要的 `LinkedHashMap` 只在最终 CPS adapter
生成，且会移除 signed/API/browser URL 等不应泄露的字段。

## 资源预算与流式限制

所有远端内容都按不可信资源处理。限制分页页数、条目数、累计响应字节、单文件大小、并发、等待队列、
请求时间和重定向跳数，是为了同时保护 Controller heap、Agent workspace 磁盘、HTTP 连接池和 Jenkins
执行线程，而不是任意限制正常构建产物。

Release Asset 与 Runner 日志使用流式 channel 边读边计数；下载先写同目录随机临时文件，长度和路径
检查成功后才原子发布。上传固定 Content-Length 并在每次尝试前复核源文件。分页 JSON 仍有跨页累计
字节预算，因此攻击者不能用许多“单页合法”的响应绕过总量限制。

## Webhook、实时校验和最终一致性

Webhook delivery 是加速提示，不是事实来源。HMAC 在精确原始 body 上计算；插件先执行仓库级
密钥选择、常量时间签名比较、schema/origin/时间窗校验和持久化防重放，然后才解释事件。每个事件
仍会用配置的凭据从 CNB 重新读取 ref 或 PR revision。

SCM indexing 和仓库动态轮询用于修复通知丢失、乱序和 Controller 重启。轮询 fallback 按授权上下文
隔离，持久化已完成 UTC 小时 cursor 和事件 ID；只有 dispatch 与 dedup 状态都持久化后才推进
cursor。传统 Job 只回补强类型 archive 字段能够精确重建的 Push/Tag，避免根据不完整历史猜测 PR。

## 传统 Trigger 与评论触发

传统 Trigger 在第一次修改 Jenkins Queue 前完成全部远端读取。一个 delivery 的 PR revision、标签、
评论和权限组成不可变实时快照；各 Job 对同一快照独立执行自己的事件、分支、Draft、标签和评论策略。
某个 Job 所需的标签或提交字段无权限读取时，只让该 Job fail closed，不会阻止同仓库未启用该策略的
其他 Job。

目标分支 push 扩展为开放 PR 时，每个 Classic Job 还必须存在与实时 PR 源仓库等价的 GitSCM remote。
插件把 GitSCM 配置中的精确 URI 写入 `RevisionParameterAction`；remote 缺失时不入队，避免 Git 插件
忽略 revision action 后回退到默认分支。Multibranch 通过对应 SCM Source 完成同一约束。

配置页仓库标签目录使用固定 worker/queue/timeout/TTL/条目上限，并按 Server、仓库、凭据和 Item
隔离 single-flight/cache。表单超时只返回“不可用”警告，不把远端异常正文或凭据写入响应。

PR 评论额外要求：

1. 评论 ID 可寻址，实时正文和作者与签名 payload 完全一致；
2. 评论者当前仍具有目标仓库配置的最低角色；
3. RE2/J 对完整评论执行线性时间匹配；
4. 当前 PR revision 仍一致。

任意未找到、未授权、评论已编辑/删除、revision 已过期或权限不足都会关闭触发。可重试的 API、凭据
和配置故障会让 delivery 失败并释放 replay claim，而不是错误地标记为已处理。

Dispatcher 先构造全部已验证候选，再在 Jenkins Queue lock 下原子入队。如果任一 enqueue 失败，
本批新增条目按逆序回滚。排队成功后，取消 superseded pending/running build 属于隔离的 best-effort
操作；其局部故障不会把已经接受的 delivery 变成 500 重试。

Multibranch 评论事件不走通用 SCM event 路径。只有显式配置评论 Trigger Trait 的 CNB Source 才会
被考虑，而且仍必须重新通过普通 source fetch、criteria、filter、authority、source identity 和
已存在 PR child identity；不存在的 child 不会因一条评论被隐式创建。

## Fork 信任边界

Fork PR 默认不信任。HEAD/MERGE discovery 与“从哪里读取 Jenkinsfile”是两个独立决策：未经
authority 信任时，变更代码仍可被检出测试，但 Jenkinsfile 从目标分支读取。成员信任使用目标仓库
实时角色查询，`401/403/404` 或未知角色均 fail closed。

API 凭据、checkout 凭据、结果上报凭据可以分离。子项目只能使用自身 SCM Source 的 item-scoped
API credential；不会回退到其他 Job 或第一个匹配 Source 的凭据。

## 结果上报

CNB 公开 API 只能读取原生 Commit Status，不能写入。插件因此只建模实际支持的 PR 评论、Commit
annotation 和 Tag annotation，不提供名为“Commit Status publisher”的伪抽象。

CNB Badge 位于同一个 `CnbClient` seam 后，但与生命周期上报分离。`listBadges`、`getBadge` 和
`uploadBadge` 隐藏 Swagger envelope/线上裸数组差异、GET branch body、`.json` 后缀、斜杠路径编码、
同源 URL 与重试语义；Pipeline 只接收 CPS-safe Map。Badge 用于 README/视觉展示，不参与 required
status 或合并门禁，也不会在没有 CNB 文档化 Jenkins key 时自动上报。

annotation 使用 context 与哈希派生的 `jenkins_..._` key。CNB key 仅允许 `[0-9A-Za-z_-]`，并按 key
合并 PUT；插件只写自己的命名空间，
不执行跨生产者 read-modify-write。每个 Pipeline context 拥有独立持久化 action；queued、running、
final、retry 和 Controller restart 统一走幂等 reconciliation。

批量 Commit annotation 查询使用 CNB 的只读 POST；请求体相同的 429/5xx 可安全重试。CNB 的 20 个 SHA、
5 个过滤 key 和 40 位 SHA 约束在 Pipeline 与 HTTP 两层校验。Ktor 普通 JSON 转换会先物化完整响应，
因此该端点在 `kotlinx.serialization` 解码前以 4 MiB 字节预算读取，避免异常响应耗尽 Controller 堆。

## Release Asset 传输

Release metadata 与文件内容分离。列表和详情进入强类型领域模型；CNB 返回的上传票据和下载 URL
只在 transport 内部存活，不进入 build.xml、Pipeline 返回值或日志。

上传使用可重复打开、固定长度的 workspace 输入流；源文件在每次尝试前核对大小，signed PUT 不带
Bearer，也不会自动重放非幂等失败。Ktor 使用 `ByteReadChannel` 流式传输，不将 Asset
整体物化为内存中的 `ByteArray` 或 JSON 值。

下载直接写随机临时文件，同时限制 Content-Length 和实际字节数；成功后才在 Agent workspace
内执行 real-path 校验与原子 rename，失败则删除临时文件。覆盖现有目标和所有删除操作都要求
用户显式确认。

## 持久化与升级

会跨 Controller 重启的对象只保存非敏感、规范化字段。Jenkins/XStream 可能绕过 Kotlin 构造器，
因此 Trigger、Trait、Server、SCM head/revision 和状态 action 都通过 `readResolve` 重新验证；旧配置
缺少安全默认值时补为更严格默认，非法旧值则禁用相关能力，而不是放宽策略。
