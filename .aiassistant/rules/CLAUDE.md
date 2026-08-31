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

## 5. Import Grouping Comments
* Use Kotlin collapsible region/endregion markers for each top-level group
* Use the following format: "// 1. Organization Internal", "// 1.1 Spartan Laboratories"
* Use comments to organize imports into the following groups:
* * 1. "Organization Internal" (evaluate subgroups from most specific to least specific; 1.1 is the residual bucket)
* * 1.1 Spartan Laboratories (anything that starts with com.spartanlabs unless it falls into another 1.X category)
* * 1.2 Spartan Gaming (anything that starts with com.spartanlabs.gaming)
* * 2. "Intended Function" (imports that directly support the core algorithm of the class/file and any owned vals/vars)
* * 2.1 (if a subfunction(s) exists) specific subfunctions
* * 3. Utility / Catch-all
* * 3.1 Java Standard library
* * 3.2 Kotlin
* * 3.2.1 Standard library
* * 3.2.2 Kotlinx extensions
* * 3.3 Third party
* * 3.3.1 Specific organization
* * 4. Programming Infrastructure and Support
* * 4.1 Logging
* * 4.2 Error Handling
* * 4.3 Testing (for test classes (in src/test))
* * 4.4 Profiling (if present)
* If a group/subgroup is not present a comment line is not required.
* Within a final specified group/subgroup, group alphabetically by fully qualified import path
* For imports where multiple groups are appropriate use the following top-level group priority: 1,4,(2/3)
* Create a group or subgroup even if only one member of that group exists
* Always Clean up unused imports