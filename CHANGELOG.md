# Changelog

All notable changes are documented in GitHub Releases. The project follows Semantic Versioning and
Conventional Commits.

## [1.0.0] - Unreleased

- CNB server profiles, token credentials, JCasC, and hardened OpenAPI transport.
- Organization Folder and Multibranch discovery for branches, tags, and pull requests.
- HTTPS Git checkout, fork trust policies, local merge builds, and REST-backed lightweight checkout.
- Repository-bound signed webhook ingestion for the native flat `cnbcool/webhook:v1.0.2` payload, plus persistent
  event-archive recovery.
- A 1 MiB declared and chunked webhook body limit enforced before JSON processing.
- Classic-job triggers, Pipeline metadata step, PR comments, and commit annotations.
- Strongly typed PR, review, Build, Release, and Release Asset Pipeline/Freestyle operations.
- Open-PR rebuilds for source/target pushes with exact, verified Classic Git revision checkout.
- Repository-label completion and validation for Classic and Multibranch configuration.
- CNB PR/commit changelog links and optional Classic build descriptions.
- CNB-compatible annotation key namespaces with fail-fast mutation validation.
- Strongly typed, bounded batch reads for Commit annotations in API and Pipeline.
- Strongly typed CNB Badge list, JSON read, and explicit upload steps with README-ready URLs.
- Ktor 3.5.1 Apache5 transport integrated with kotlinx.serialization and bounded streaming transfers.
- Production hardening for bounded API responses, restart-safe workspace transfers, durable event recovery,
  recoverable metadata/webhook delivery, escaped build descriptions, and release-gating security scans.
- Java 17 bytecode, Jenkins 2.541.3 minimum, Java 17/21/25 CI, and an enforced 86% line-coverage floor.
