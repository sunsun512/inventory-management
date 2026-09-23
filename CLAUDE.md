# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Inventory Management API

Planned features (from README.md, Korean):
- **입고 (stock-in)**: increase a product's current stock quantity; if the product isn't registered yet, register it as new before processing the stock-in.
- **출고 (stock-out)**: decrease a product's current stock quantity; stock quantity must never go negative.
- **재고 (inventory)**: check a product's current stock quantity; view stock change history.

## Commands

Build tool is Gradle (via the wrapper — do not assume a global `gradle` install).

```bash
./gradlew build              # full build (compiles + runs tests)
./gradlew test                # run all tests
./gradlew test --tests "com.example.inventory.management.InventroyManagementApplicationTests"  # single test class
./gradlew test --tests "*.InventroyManagementApplicationTests.컨텍스트가_정상적으로_로드된다"  # single test method
./gradlew bootRun              # run the application locally
./gradlew clean                # clean build outputs
```

## Architecture

- **Stack**: Java 17 (toolchain-pinned), Spring Boot 4.1.1, Spring MVC (`spring-boot-starter-webmvc`), Gradle with `io.spring.dependency-management`.
- **Base package**: `com.example.inventory.management`.
- **Testing**: JUnit 5 via `spring-boot-starter-webmvc-test`, run through the JUnit Platform (`useJUnitPlatform()` in `build.gradle`).
- No persistence layer, web layer, or domain model is wired up yet — no database dependency is declared in `build.gradle`, so a DB choice/integration is still pending (tracked as "IM4 - 데이터베이스 연동" in the README's task list). When implementing the features above, note that persisted stock quantities and stock-change history are core requirements, not incidental — model them accordingly from the start rather than bolting history tracking on later.
