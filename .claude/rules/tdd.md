# New features: TDD with JUnit unit tests

Develop every new feature test-first, in this order:

1. **Write the test first**: a JUnit 5 unit test that covers the behavior to be added. Do not write production code before the test exists.
2. **Confirm Red**: run the test (`./gradlew test --tests "<TestClass>"`) and confirm it fails *for the expected reason* (the behavior is missing, not a typo or a compile error elsewhere). Show the failing output.
3. **Implement**: write the minimum production code needed to make the test pass.
4. **Confirm Green**: run the test again and confirm it passes, then run the full suite (`./gradlew test`) to make sure nothing else broke. Show the passing output.
5. Refactor only while tests stay green.

Never skip the Red step, and never report a feature as done without showing the Green result.
