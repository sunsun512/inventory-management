# Code changes: TDD with JUnit 5 tests

## When this applies

Only when implementing or changing behavior in code: production Java code, and SQL scripts or
application configuration (`application*.yml`) that change how the application behaves.

It does not apply to changes that do not touch code behavior, such as:
- documentation (README, CLAUDE.md, code comments, Swagger/OpenAPI description text)
- Swagger/OpenAPI documentation (annotations such as `@Operation`/`@Parameter`/`@Schema`, `OpenApiConfig`) —
  no tests are written for API docs
- `.claude` rules and other tooling/editor settings
- analysis, investigation, or proposals without code changes
- git operations (branching, committing, pushing, pull requests)

## Choosing the test type

Use the lightest test that can actually prove the behavior:

- **Integration test (default for most changes)**: anything involving the database, SQL/migrations, transactions
  and locking, HTTP request/response mapping, validation, error codes, or `application*.yml` settings.
  Extend `support/AbstractIntegrationTest` (`@SpringBootTest` against a real PostgreSQL via Testcontainers,
  shared by all subclasses). Do not replace the database with mocks or H2 — the behavior under test
  (atomic `UPDATE`, constraints, advisory locks, timeouts) only exists in PostgreSQL.
  - The Spring context is shared across subclasses; if a test needs different properties, override them with
    its own `@SpringBootTest(properties = ...)` on the subclass (see `RateLimitIntegrationTest`), which starts a
    separate context.
  - Requires Docker to be running.
- **Plain unit test**: pure logic with no Spring context or database, e.g. `PostRateLimiterTest` (with
  `FakeTimeMeter`), `RateLimitInterceptorTest`, `PageResponseTest`.

## Process

Develop every code change test-first, in this order:

1. **Write the test first**: a JUnit 5 test of the type chosen above that covers the behavior to be added or changed. Do not write production code before the test exists.
2. **Confirm Red**: run the test (`./gradlew test --tests "<TestClass>"`) and confirm it fails *for the expected reason* (the behavior is missing, not a typo, a compile error elsewhere, or Docker not running). Show the failing output.
3. **Implement**: write the minimum production code needed to make the test pass.
4. **Confirm Green**: run the test again and confirm it passes, then run the full suite (`./gradlew test`) to make sure nothing else broke. Show the passing output.
5. Refactor only while tests stay green.

Never skip the Red step, and never report a code change as done without showing the Green result.
