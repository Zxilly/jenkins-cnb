# Contributing

Contributions are welcome through issues and pull requests.

1. Use Java 17, 21, or 25 and the checked-in Maven Wrapper.
2. Keep the CNB public API boundary explicit; do not add calls to undocumented status or webhook CRUD
   endpoints.
3. Add focused unit tests and Jenkins test-harness integration tests for behavior, failure handling,
   persistence, lifecycle, and security boundaries.
4. Run `./mvnw -B -ntp clean verify` before opening a pull request.
5. Use [Conventional Commits](https://www.conventionalcommits.org/) for commit and pull-request titles.

Tests run in isolated JVMs with a default fork count of `0.45C`. Use `-DforkCount=1` on a
memory-constrained machine or when reproducing an order-sensitive failure; do not enable JUnit method-level
parallelism for Jenkins Test Harness tests.

The build enforces aggregate JaCoCo line coverage above 85% (currently a 0.86 minimum).

Report vulnerabilities according to [SECURITY.md](SECURITY.md), not through public issues.
