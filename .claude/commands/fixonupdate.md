---
description: Watch Maven Central for a new GameTools release, then bump and fix the code
---

Monitor for the release of a new version of the MyGameTools library. Poll every 2 minutes.
Update to the new library and make any necessary changes.

## Details

- Current dependency: `io.github.spartanlabsgaming:GameTools` in `build.gradle.kts`
  (line with `implementation("io.github.spartanlabsgaming:GameTools:<version>")`).
- Poll Maven Central for the latest version, e.g.:
  `https://repo1.maven.org/maven2/io/github/spartanlabsgaming/GameTools/maven-metadata.xml`
  Use the `Monitor` tool with a 2-minute (`sleep 120`) poll loop that emits a line only when
  `<latest>` / `<release>` differs from the version currently pinned in `build.gradle.kts`.
- When a newer version appears:
  1. Update the version in `build.gradle.kts`.
  2. Refresh dependencies / re-sync Gradle.
  3. Diff the sources jar old vs new to find API changes:
     `C:\Users\spart\.gradle\caches\modules-2\files-2.1\io.github.spartanlabsgaming\GameTools\<version>\*\GameTools-<version>-sources.jar`
     — unzip old vs new and `diff -ru`; check the sibling `.pom` for dependency changes.
  4. Apply any source changes MyGameServer needs to compile and behave correctly.
  5. Build (`./gradlew build`) and run the tests to confirm.
  6. Report what changed in the library and what was updated here.
