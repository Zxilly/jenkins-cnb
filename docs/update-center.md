# CNB 自定义 Update Center

简体中文 | [English](update-center.en.md)

CNB 插件通过 GitHub Release 发布 HPI，GitHub Pages 托管签名更新元数据。
插件的 Maven 坐标为 `dev.zxilly.jenkins.cnb:cnb`。

站点地址：`https://zxilly.github.io/jenkins-cnb/update-center.json`。只有维护者完成下方的首次
配置并发布正式版本后，这个地址才可用。

## Jenkins 管理员配置

1. 下载仓库中的 [签名证书](cnb-update-center.crt)。运行
   `keytool -printcert -file cnb-update-center.crt`，核对 SHA-256 指纹：

   ```text
   46:A7:00:F0:80:38:0A:E2:3B:A7:67:4E:16:0C:AC:F2:3B:2B:06:9E:FE:24:35:0E:4F:A7:3A:95:DA:E7:18:DC
   ```

2. 将证书复制到 Controller 的 `$JENKINS_HOME/update-center-rootCAs/cnb-update-center.crt`。
   创建目录并确保运行 Jenkins 的用户可读；容器部署应将文件持久化到 Jenkins Home。
3. 用管理员账号打开 **Manage Jenkins > Script Console**，执行以下脚本添加更新源：

```groovy
import jenkins.model.Jenkins
import hudson.model.UpdateSite

def center = Jenkins.get().updateCenter
center.sites.removeAll { it.id == 'cnb' }
center.sites.add(new UpdateSite('cnb', 'https://zxilly.github.io/jenkins-cnb/update-center.json'))
center.save()
println center.sites.collect { "${it.id}: ${it.url}" }.join('\n')
```

4. 在 **Manage Jenkins > Plugins** 点击 **Check now**，随后在可安装插件中搜索
   **CNB Integration**。安装时勾选依赖，完成后重启 Jenkins。以后可在同一界面更新插件。

保留 `default` 官方更新源，用于下载 Git、Credentials、Pipeline 等依赖。Advanced settings
中的单个 Update Site URL 会替换官方源，因此这里通过脚本添加额外站点。

Jenkins 原生验证元数据签名和 HPI 校验和。此配置只增加 CNB 证书的信任，不需要关闭签名验证。
若证书未安装、已过期、指纹不符或元数据被篡改，应先解决对应问题再刷新。

## 维护者首次配置

以下步骤在可信任的本机执行。示例使用 PowerShell 7、JDK 17+ 的 `keytool` 和已登录的 GitHub CLI。

1. 在仓库根目录生成专用签名密钥。`.local/` 已被 Git 忽略；另外保留离线备份。
   PKCS12 密码不会写入命令行参数：

```powershell
New-Item -ItemType Directory -Path .local/update-center -Force | Out-Null
$env:UC_KEYSTORE_PASSWORD = Read-Host 'Signing keystore password' -MaskInput
keytool -genkeypair -alias cnb -keyalg RSA -keysize 3072 `
  -dname 'CN=CNB Jenkins Update Center' -ext bc=ca:true `
  -ext ku=digitalSignature,keyCertSign -validity 3650 -storetype PKCS12 `
  -keystore .local/update-center/cnb-signing.p12 -storepass:env UC_KEYSTORE_PASSWORD
keytool -exportcert -rfc -alias cnb -keystore .local/update-center/cnb-signing.p12 `
  -storepass:env UC_KEYSTORE_PASSWORD -file docs/cnb-update-center.crt
keytool -printcert -file docs/cnb-update-center.crt
```

2. 将公开证书 `docs/cnb-update-center.crt` 和新指纹一起提交到仓库，更新本页及英文版。
   发布工作流会验证 Secret 中的证书与仓库证书一致。管理员应通过仓库核对证书，
   不能只用更新站点自己给出的指纹核对同站点下载的证书。
3. 在 GitHub **Settings > Environments** 创建 `update-center` 环境，将部署分支限制为
   `master` 和正式发布标签 `v*`，按需配置审批。将下面两个 Secret 存入该环境：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.local/update-center/cnb-signing.p12'))) |
  gh secret set UC_KEYSTORE_BASE64 --env update-center --repo Zxilly/jenkins-cnb
$env:UC_KEYSTORE_PASSWORD |
  gh secret set UC_KEYSTORE_PASSWORD --env update-center --repo Zxilly/jenkins-cnb
Remove-Item Env:UC_KEYSTORE_PASSWORD
```

4. 在 **Settings > Pages > Build and deployment** 将 Source 设为 **GitHub Actions**。
   `github-pages` 环境需要允许 `master` 和正式发布标签 `v*` 部署。
5. 按现有发布流程创建正式 SemVer 标签，例如 `v1.0.0`。HPI 发布成功后，Release 工作流
   自动生成并部署更新站点。已有正式版本时，也可在 **Actions > Publish Update Center**
   选择 `master` 手动运行，以恢复部署或续期证书。

私钥不进入发布产物。公开证书和指纹会与元数据一起部署。维护者必须在证书过期前续期并通知
管理员更新信任证书；轮换密钥时也需要重新安装证书。

## 发布行为与本地验证

- 更新源只收录 `cnb`。依赖列表、最低 Jenkins/Java 版本和 HPI 校验和从发布 HPI 读取。
- 从 GitHub Release 中选择版本号最高的正式 SemVer 版本；忽略 draft、prerelease 和缺少 HPI
  的 Release。补发旧版本不会使更新源降级。
- `-rc` 等预发布版本保留在 GitHub Releases，不推送到正式更新源。
- 输出 `update-center.json`（Jenkins JSONP）、`update-center.actual.json`（原始 JSON）、
  `cnb-update-center.crt` 和 `certificate-sha256.txt`。
- 生成器使用 Jenkins 相同的 JSON canonical writer，签名为 RSA/SHA-512。回归测试直接调用
  Jenkins `2.541.3` 的签名验证器，检查有效签名、篡改和未信任证书。

```powershell
./mvnw.cmd -B -ntp -f update-center/pom.xml verify
python -m unittest discover -s scripts -p 'test_*.py'
$env:UC_KEYSTORE_PASSWORD = Read-Host 'Signing keystore password' -MaskInput
java -jar update-center/target/cnb-update-center-1.0.0.jar `
  target/cnb.hpi 1.0.0 `
  https://github.com/Zxilly/jenkins-cnb/releases/download/v1.0.0/cnb.hpi `
  work/update-site .local/update-center/cnb-signing.p12
Remove-Item Env:UC_KEYSTORE_PASSWORD
```

本地生成时 HPI 必须为对应的正式版本，不能使用 `SNAPSHOT` HPI。

签名及信任目录依据 [Jenkins JSONSignatureValidator 源码](https://github.com/jenkinsci/jenkins/blob/jenkins-2.541.3/core/src/main/java/jenkins/util/JSONSignatureValidator.java)。
