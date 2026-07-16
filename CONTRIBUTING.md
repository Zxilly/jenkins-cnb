# Contributing

Contributions are welcome through issues and pull requests.

1. Run Maven with Java 21 or 25 and the checked-in Maven Wrapper. Install a Java 17 Maven toolchain when
   validating the minimum runtime.
2. Keep the CNB public API boundary explicit; do not add calls to undocumented status or webhook CRUD
   endpoints.
3. Add focused unit tests and Jenkins test-harness integration tests for behavior, failure handling,
   persistence, lifecycle, and security boundaries.
4. Run `./mvnw -B -ntp clean verify` before opening a pull request.
5. Use [Conventional Commits](https://www.conventionalcommits.org/) for commit and pull-request titles.

Tests run in isolated JVMs with a local default fork count of `0.45C`; CI overrides it to `1C` so each
available core can run one test class fork. Use `-DforkCount=1` on a memory-constrained machine or when
reproducing an order-sensitive failure; do not enable JUnit method-level parallelism for Jenkins Test Harness
tests.

The latest Jenkins annotation processors require Java 21 to build. CI still runs the complete test suite on
Java 17 by selecting it only for Surefire through Maven Toolchains; production bytecode targets Java 17.

The build enforces aggregate JaCoCo line coverage above 85% (currently a 0.86 minimum).

Report vulnerabilities according to [SECURITY.md](SECURITY.md), not through public issues.
