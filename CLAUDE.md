# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Inventory Management API (requirements and design notes in README.md, Korean):
- **입고 (stock-in)**: increase a product's current stock quantity; if the product isn't registered yet, register it as new before processing the stock-in.
- **출고 (stock-out)**: decrease a product's current stock quantity; stock quantity must never go negative.
- **재고 (inventory)**: check a product's current stock quantity; view stock change history; list products (filter by product code) and view product details.

## Commands

Build tool is Gradle (via the wrapper — do not assume a global `gradle` install).

```bash
# Start local PostgreSQL
docker compose -f local/docker-compose.yml up -d

./gradlew build              # full build (compiles + runs tests)
./gradlew test                # run all tests (needs Docker running — Testcontainers); also writes the JaCoCo report
./gradlew test --tests "com.example.inventory.management.InventroyManagementApplicationTests"  # single test class
./gradlew test --tests "*.InventroyManagementApplicationTests.컨텍스트가_정상적으로_로드된다"  # single test method
./gradlew bootRun --args='--spring.profiles.active=local'  # run locally (local profile: local DB, SQL logging, seed data)
./gradlew clean                # clean build outputs
```

## Architecture

- **Stack**: Java 17 (toolchain-pinned), Spring Boot 4.1.1, Spring MVC (`spring-boot-starter-webmvc`), Spring Data JPA + QueryDSL (OpenFeign fork), PostgreSQL 17 with Flyway, Bean Validation, springdoc-openapi, Actuator, Bucket4j + Caffeine (per-IP POST rate limit), Lombok (use `@Slf4j` for loggers), Micrometer Tracing with the OpenTelemetry bridge (traceId in logs and the `X-Trace-Id` response header; no exporter).
- **Base package**: `com.example.inventory.management`, split by feature (`common/`, `product/`, `stock/`). Each feature has `api/` (controllers; OpenAPI annotations live on `*Api` interfaces), `command/` (writes), `query/` (reads), `domain/` (entities, repositories). Dependency direction is `api → command / query → domain`; `command` and `query` never reference each other.
- **Persistence**: schema is owned by Flyway migrations in `src/main/resources/db/migration` (Hibernate `ddl-auto: validate` is set only in the `local` profile). Stock quantity lives on `product`; every change is recorded in `stock_history`. `src/main/resources/data.sql` seeds local data only when tables are empty.
- **Stock changes**: deduplicated by client-supplied `requestId`; quantity updated via atomic `UPDATE` with a DB constraint preventing negatives; Postgres `lock_timeout` / `statement_timeout` map to 503 `STOCK_LOCK_TIMEOUT`. Error codes are in `common/exception/ErrorCode`.
- **Testing**: JUnit 5 via `spring-boot-starter-webmvc-test`. Integration tests extend `support/AbstractIntegrationTest` and run against a real Postgres via Testcontainers.

## See Also

- [.claude/rules/tdd.md](.claude/rules/tdd.md) — read before changing Java code, behavior-affecting SQL, or `application*.yml`: test-first (Red → Green) workflow; not for docs/OpenAPI-only changes.
- [.claude/rules/git-branch.md](.claude/rules/git-branch.md) — read before committing or pushing: never commit/push on `develop` or `master`; use `feature/IM<n>-<short-description>` branches and PRs.
- [.claude/rules/db-migration.md](.claude/rules/db-migration.md) — read before adding or editing Flyway migrations: never modify committed migrations; confirm with the user before any `DROP`/`TRUNCATE`/data-deleting change.
- [.claude/rules/package-docs.md](.claude/rules/package-docs.md) — read before changing a package: open that package's doc first.
- [__workspace/README.md](__workspace/README.md) — index of per-package guides for `common`, `product`, and `stock`; read when you need a package's details.
- [README.md](README.md) — read for the full Korean requirements, design notes, API spec, environment variables, and run/test instructions.
