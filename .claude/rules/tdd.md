# Code changes: TDD with JUnit unit tests

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

## Process

Develop every code change test-first, in this order:

1. **Write the test first**: a JUnit 5 unit test that covers the behavior to be added or changed. Do not write production code before the test exists.
2. **Confirm Red**: run the test (`./gradlew test --tests "<TestClass>"`) and confirm it fails *for the expected reason* (the behavior is missing, not a typo or a compile error elsewhere). Show the failing output.
3. **Implement**: write the minimum production code needed to make the test pass.
4. **Confirm Green**: run the test again and confirm it passes, then run the full suite (`./gradlew test`) to make sure nothing else broke. Show the passing output.
5. Refactor only while tests stay green.

Never skip the Red step, and never report a code change as done without showing the Green result.
