# Final implementation: adopt GameTools 5.0.0's `ClientCommand` protocol

The as-built record for
[SpartanLabsGaming/MyGameServer#6](https://github.com/SpartanLabsGaming/MyGameServer/issues/6),
*"Address client commands by stable entity id (GameTools 3.1.0)"*. The plan is
[plan.md](plan.md).

## What was built

All three PRs were squash-merged into `master` on 2026-09-07 (UTC):

- **PR [#8](https://github.com/SpartanLabsGaming/MyGameServer/pull/8)**, *"Adopt GameTools
  5.0.0; entity-id command addressing (interim)"*, merged at 05:09 as `ee579e1`. It referenced
  #6 and left it open.
- **PR [#9](https://github.com/SpartanLabsGaming/MyGameServer/pull/9)**, *"docs: point the
  command-adoption plan at MyGameTools#39"*, merged at 05:12 as `afc5cd4`.
- **PR [#10](https://github.com/SpartanLabsGaming/MyGameServer/pull/10)**, *"Adopt GameTools
  5.0.0 ClientCommand protocol (#6)"*, merged at 16:57 as `1ec4427`, closed #6. It set the
  version to `2.0.0` in `build.gradle.kts`. No tag or release has been cut.

## From plan.md — Header

Moved verbatim from the plan's header. "The header note above" and "Open decision 4" refer to
[plan.md](plan.md).

- **Status:** implemented on `feature/issue-6-client-command-protocol` (this document committed
  alongside the implementation, per the header note above). Open decision 4 resolved: **merge
  the server now, gate the release on GameGraphics#1** (it only ever degrades to no-op).
