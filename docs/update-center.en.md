# CNB custom Update Center

[简体中文](update-center.md) | English

GitHub Releases hosts the HPI, and GitHub Pages hosts signed update metadata.
Maven coordinates: `dev.zxilly.jenkins.cnb:cnb`.

Site URL: `https://zxilly.github.io/jenkins-cnb/update-center.json`. The maintainer must complete the
initial configuration below and publish a stable release before this URL works.

## Jenkins administrator setup

1. Download the repository's [signing certificate](cnb-update-center.crt). Run
   `keytool -printcert -file cnb-update-center.crt` and check its SHA-256 fingerprint:

   ```text
   46:A7:00:F0:80:38:0A:E2:3B:A7:67:4E:16:0C:AC:F2:3B:2B:06:9E:FE:24:35:0E:4F:A7:3A:95:DA:E7:18:DC
   ```

2. Copy it to `$JENKINS_HOME/update-center-rootCAs/cnb-update-center.crt` on the controller. Create the
   directory and make the file readable by Jenkins. Persist it in Jenkins Home for container deployments.
3. Open **Manage Jenkins > Script Console** as an administrator and add the extra site:

```groovy
import jenkins.model.Jenkins
import hudson.model.UpdateSite

def center = Jenkins.get().updateCenter
center.sites.removeAll { it.id == 'cnb' }
center.sites.add(new UpdateSite('cnb', 'https://zxilly.github.io/jenkins-cnb/update-center.json'))
center.save()
println center.sites.collect { "${it.id}: ${it.url}" }.join('\n')
```

4. Click **Check now** under **Manage Jenkins > Plugins**, search available plugins for
   **CNB Integration**, and install it with its dependencies. Restart Jenkins afterward. Future
   versions appear in the same plugin manager.

Keep the official `default` site enabled for Git, Credentials, and Pipeline dependencies. The single
Update Site URL in Advanced settings replaces the default source; the script adds a separate site.

Jenkins validates metadata signatures and HPI checksums. This setup adds trust for the CNB certificate
and keeps signature verification enabled. Resolve missing, expired, or mismatched certificates and
tampered metadata before refreshing.

## Initial maintainer configuration

Run these steps on a trusted computer. Examples use PowerShell 7, JDK 17+ `keytool`, and an authenticated GitHub CLI.

1. Generate a dedicated signing key from the repository root. Git ignores `.local/`;
   keep an offline backup. The password is passed through the environment:

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

2. Commit the public certificate at `docs/cnb-update-center.crt` and update the fingerprint in both
   language versions of this guide. The publishing workflow checks the secret certificate against the
   repository certificate. Administrators should verify it through the repository; the fingerprint
   served alongside the certificate is insufficient on its own.
3. Create a GitHub environment named `update-center` under **Settings > Environments**, allow `master`
   and stable release tags `v*`, and configure approvals as needed. Add these two environment secrets:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.local/update-center/cnb-signing.p12'))) |
  gh secret set UC_KEYSTORE_BASE64 --env update-center --repo Zxilly/jenkins-cnb
$env:UC_KEYSTORE_PASSWORD |
  gh secret set UC_KEYSTORE_PASSWORD --env update-center --repo Zxilly/jenkins-cnb
Remove-Item Env:UC_KEYSTORE_PASSWORD
```

4. Set **Settings > Pages > Build and deployment > Source** to **GitHub Actions**. Allow `master` and
   stable release tags `v*` in the `github-pages` deployment environment.
5. Publish a stable SemVer tag, such as `v1.0.0`, through the existing release workflow. After publishing
   the HPI, it generates and deploys the update site. When a stable release already exists, you can also
   run **Actions > Publish Update Center** manually on `master` to restore deployment or renew a certificate.

The private key is excluded from site artifacts. The public certificate and fingerprint are deployed
with the metadata. Renew certificates before expiry and notify administrators to update their trusted
certificate. Key rotation also requires installing the new certificate.

## Release behavior and local verification

- The site contains only `cnb`. Dependencies, minimum Jenkins/Java versions, and checksums come from the released HPI.
- The highest stable SemVer release is selected, ignoring drafts, prereleases, and releases without an
  HPI. Publishing an older version does not downgrade the site.
- Prereleases such as `-rc` stay in GitHub Releases and are excluded from the stable update site.
- Output includes `update-center.json` (Jenkins JSONP), `update-center.actual.json` (raw JSON),
  `cnb-update-center.crt`, and `certificate-sha256.txt`.
- The generator uses Jenkins' canonical JSON writer and RSA/SHA-512 signatures. Regression tests call
  the Jenkins `2.541.3` validator directly to check valid signatures, tampering, and untrusted certificates.

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

The local HPI must match the stable release version; a `SNAPSHOT` HPI is rejected.

Signature and trust directory behavior follows [Jenkins JSONSignatureValidator](https://github.com/jenkinsci/jenkins/blob/jenkins-2.541.3/core/src/main/java/jenkins/util/JSONSignatureValidator.java).
