# Security policy

Please report suspected vulnerabilities privately through
[GitHub Security Advisories](https://github.com/Zxilly/jenkins-cnb/security/advisories/new). Do not
open a public issue with exploit details, credentials, webhook bodies, or controller URLs.

Include the affected plugin/Jenkins versions, deployment topology, reproduction steps, and impact.
Use synthetic secrets only. You should receive an acknowledgement within seven days.

Only the latest released version is supported for security fixes. Rotate any credential that may have
been included in logs or payloads before sharing diagnostic material.

## Deployment hardening

- Terminate TLS at Jenkins or a trusted reverse proxy and apply normal request-rate and body-size
  limits to `/cnb-webhook/`; the plugin enforces HMAC authentication, freshness, and persistent
  replay protection.
- Use a different webhook key for every repository. Never reuse an OpenAPI or Git token as an HMAC
  key.
- Leave private-network and insecure-HTTP endpoint options disabled for `cnb.cool`. With private
  networking disabled, every address returned for a destination must be public unicast. DNS is
  resolved and checked at the Apache5 engine's socket-connection seam, and that exact validated
  address set is passed to the connection operator. Apache5 is used only as Ktor Client's official
  engine; Ktor owns HTTP execution, timeouts, response handling, and client lifecycle.
- Enabling private networking deliberately permits private destinations. Ktor remains the transport
  owner and applies Jenkins `ProxyConfiguration` through its Apache5 engine. This mode is only for
  administrator-controlled private CNB installations.
- Plain HTTP remains a separate explicit opt-in. Signed external transfer targets accept it only
  when private networking and insecure HTTP are both enabled; repository-event targets always
  require HTTPS.
- Automatic redirects are disabled in Ktor. The bounded repository-event and Release Asset flows
  resolve and validate every hop before sending it, including scheme, host, user-info, fragment,
  and, unless private networking is enabled, public-address policy.
- The CNB bearer credential and CNB/JSON content-negotiation headers are never forwarded from the
  API origin to object storage. Signed URLs and transfer tickets remain transport-local;
  credentials, secrets, and raw response bodies are never written to build state or logs.
- Use separate least-privilege scan, checkout, and result-reporting credentials and rotate them on a
  documented schedule.
