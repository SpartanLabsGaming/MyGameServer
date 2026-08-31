---
apply: always
---

Apply these strict rules to all code generation, refactoring, and test design.

## 1. Paradigm & Style
* Use **Kotlin** idioms and modern language features.
* Blend **Object-Oriented Programming** with **Functional Programming**.
* Use classes for domain modeling and encapsulation.
* Use pure functions, immutability (`val`), and transformation functions (`map`, `flatMap`, `filter`) for data manipulation.

## 2. Error Handling
* Never throw raw exceptions for expected failures.
* Encapsulate all operational failures using the native **`Result` class**.
* Return `Result.success(value)` or `Result.failure(exception)`.
* Prefer functional error recovery over `try-catch` blocks where appropriate.

## 3. Logging & Documentation
* Include structured **logging** statements for key lifecycle events, data flows, and failure states.
* For libraries use slf4j and for testing use a logback implementation
* Document all public classes, interfaces, and functions using formal **KDoc comments** (`/** ... */`).

## 4. Testing Structure
* Provide comprehensive unit tests using a standard framework (e.g., JUnit 5, MockK).
* Structure your test code strictly with **separate test classes per file**.
* Do not bundle multiple test classes into a single file.