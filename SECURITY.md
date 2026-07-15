# Security policy

Please report suspected vulnerabilities privately through
[GitHub Security Advisories](https://github.com/Zxilly/jenkins-cnb/security/advisories/new). Do not
open a public issue with exploit details, credentials, webhook bodies, or controller URLs.

Include the affected plugin/Jenkins versions, deployment topology, reproduction steps, and impact.
Use synthetic secrets only. You should receive an acknowledgement within seven days.

Only the latest released version is supported for security fixes. Rotate any credential that may have
been included in logs or payloads before sharing diagnostic material.

## Deployment hardening

- Terminate TLS at Jenkins or a trusted reverse proxy and apply normal request-rate limits to
  `/cnb-webhook/`; the plugin additionally enforces a 256 KiB body limit, HMAC authentication,
  freshness, and persistent replay protection.
- Use a different webhook key for every repository. Never reuse an OpenAPI or Git token as an HMAC
  key.
- Leave private-network and insecure-HTTP endpoint options disabled for `cnb.cool`. Enabling private
  endpoints deliberately expands the controller's outbound network reach and is intended only for
  administrator-controlled private CNB installations.
- The controller revalidates configured API hosts and every repository-event redirect immediately
  before connecting, rejects private destinations by default, disables automatic redirects, and
  never forwards authorization to object storage. Administrators must still secure controller DNS
  and proxy configuration against local compromise because Java's HTTP transport resolves the final
  peer after policy validation.
- Use separate least-privilege scan, checkout, and result-reporting credentials and rotate them on a
  documented schedule.
