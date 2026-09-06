# Plan: address client commands by stable entity id

**Status:** server side implemented on `feature/issue-6-entity-id-addressing` (2026-09-05);
GameGraphics side (§6) still to do. Decisions 1 and 2 taken as recommended (cancelAttack
wired into SET_DEST/STOP; SET_SPEED/STOP now id-addressed). `resolveOwnedAlive` helper
extracted and tested (`SetDestCommandTest`, 7 tests). Tracked by MyGameServer#6.
**Depends on:** GameTools 3.1.0 (already bumped in `build.gradle.kts`)
**Closes (downstream):** MyGameTools#3 — "Broadcast snapshots have no stable identity"
**Folds in (optional):** MyGameTools#1 follow-up — wire `cancelAttack()` into move/stop
**Cross-repo:** GameGraphics must ship the matching client change in lockstep
**Order:** do this before `plan-simulation-loop-adoption.md` (both touch `Main.kt`)

---

## 1. Problem

Client commands address game objects by **position in the last `STATE` broadcast list**:

- `Main.kt` `handleClientMessage` resolves `SET_DEST` against
  `world.gameObjects.filterIsInstance<VisibleObject>()[index]`.
- `issuePlayerAttack` resolves `ATTACK <attacker> <target>` the same way.
- `SET_SPEED` / `STOP` index a *different* list — the demo-actor list `actors` (the zombies).
- GameGraphics `Viewport.selectedActor` stores a picked **index** and re-resolves it against
  each new `STATE` (`Main.kt` `selectedRaw`).

When any earlier object leaves the world between the frame a client picked an index and the
frame the server acts on the queued command, every later index shifts by one — a move or
attack lands on the wrong unit, and the client selection silently retargets. This is latent
today (nothing is removed at runtime yet) but becomes real as soon as `REMOVAL` deaths,
disconnects, or projectiles remove objects mid-game. The combat and death systems already
do this.

## 2. What GameTools 3.1.0 gives us

- `GameObject.entityId: EntityId` (`@JvmInline value class EntityId(val raw: Long)`),
  assigned exactly once by the owning `World` (via `World.add`, or on first sight in
  `gameObjects` at the top of `tick()`), never reused, never changed.
- `World.byId(id: EntityId): GameObject?` — returns `null` for an unknown / removed id, which
  is exactly the "client named something that's gone" signal we want.
- Every `DrawableSnapshot` variant now carries `val id: Long`
  (`VisibleObjectSnapshot` / `ActorSnapshot` / `AliveSnapshot`), defaulted to
  `DrawableSnapshot.UNIDENTIFIED = 0L`. `0` never resolves (a `World` never hands out `0`).

## 3. Wire protocol change

Command token **counts are unchanged**; only operand semantics change (index → id):

| Command | Before | After |
|---|---|---|
| `SET_DEST <n> <x> <y>` | `<n>` = broadcast-list index | `<n>` = `id` of that `STATE` entry |
| `ATTACK <a> <t>` | list indices | entity ids |
| `SET_SPEED <n> <speed>` | demo-actor-list index | entity id |
| `STOP <n>` | demo-actor-list index | entity id |
| `INPUT <json>` (PRESS) | aims demo actor 0 | unchanged (demo-only, positional by design) |

**Mixed-version failure mode:** an old client sends small integers (`0,1,2`) — as ids these
don't resolve, so the new server silently ignores the command. An old server given a large
id treats it as a list index — `getOrNull` returns `null`, command ignored. Both degrade to
"commands do nothing", never "wrong target". Still, the two repos must release together.

## 4. Server changes — `src/main/kotlin/Main.kt`

### 4.1 `handleClientMessage`

- Drop the `actors: List<Actor>` parameter (no longer needed once `SET_SPEED`/`STOP` go
  through `world`). `handleClientInput` keeps its own `actors` reference for PRESS.
- Add `import com.spartanlabs.gaming.gameobjects.EntityId`.
- Parse operands with `toLongOrNull()` instead of `toIntOrNull()`.

`SET_DEST`:
```kotlin
val id = parts.getOrNull(1)?.toLongOrNull()
val x  = parts.getOrNull(2)?.toDoubleOrNull()
val y  = parts.getOrNull(3)?.toDoubleOrNull()
if (id != null && x != null && y != null) {
    val target = world.byId(EntityId(id))
    if (target is Alive && target.owner != null && target.owner === players[playerName]) {
        target.cancelAttack()                 // MyGameTools#1 follow-up — see §7 decision 1
        target.destination = Point(x = x, y = y)
    }
}
```

`SET_SPEED`:
```kotlin
val id = parts.getOrNull(1)?.toLongOrNull()
val speed = parts.getOrNull(2)?.toDoubleOrNull()
if (id != null && speed != null) {
    (world.byId(EntityId(id)) as? Actor)?.let { it.speed = ModularStat(base = speed) }
}
```

`STOP`:
```kotlin
val id = parts.getOrNull(1)?.toLongOrNull()
if (id != null) {
    (world.byId(EntityId(id)) as? Actor)?.let { actor ->
        (actor as? Alive)?.cancelAttack()
        actor.destination = Point(actor.location)
    }
}
```

`ATTACK`: parse two `Long`s, delegate to `issuePlayerAttack` (below).

### 4.2 `issuePlayerAttack`

Rename params `attackerIndex`/`targetIndex` → `attackerId`/`targetId` (`Long`); resolve via
`world.byId`:
```kotlin
internal fun issuePlayerAttack(
    playerName: String, attackerId: Long, targetId: Long,
    world: World, players: Map<String, Player>
): Boolean {
    val attacker = world.byId(EntityId(attackerId)) as? Alive ?: return false
    val target   = world.byId(EntityId(targetId))   as? Alive ?: return false
    if (attacker === target) return false
    val player = players[playerName]
    if (player == null || attacker.owner !== player || target.owner === player) return false
    attacker.issueAttack(target)
    return true
}
```
The `visibles` local and the `filterIsInstance` go away. Update the KDoc (drop
"positions in the broadcast list").

### 4.3 `handleClientMessage` call site in `main()`

Drop the `actors` argument. Nothing else in `main()` changes — broadcasting still sends
`world.gameObjects.filterIsInstance<VisibleObject>()`; the client now reads `id` off each
entry instead of counting positions.

### 4.4 `disconnectPlayer` (note, no change required)

It removes the roster straight from `world.gameObjects`. `World.byId` is rebuilt from
`gameObjects` every `tick()`, so a removed unit's id stops resolving from the next tick —
which is after the disconnect is processed in the same loop iteration. No stale-hit window
in practice. (Optional tidy-up: route removals through `world.removeList` so `byId` and the
`GameEvent.EntityRemoved` bus event both fire — out of scope here.)

## 5. Server tests — `src/test/kotlin/`

Keep the flat / default-package layout the existing three test files use (see §7 decision 3).

### 5.1 `AttackCommandTest` (rewrite call sites)

- `Fixture` already does `world.add(...)`, so `aliceUnit.entityId` / `bobUnit.entityId` are
  assigned. Replace every `issuePlayerAttack("x", attackerIndex = 0, targetIndex = 1, …)`
  with `attackerId = f.aliceUnit.entityId.raw, targetId = f.bobUnit.entityId.raw`.
- `a target slot that is not an Alive is rejected` → pass `scenery.entityId.raw`.
- `an out-of-range index is rejected` → rename to `an unknown id is rejected`
  (`targetId = 999_999L`), plus `attackerId = 0L` (UNIDENTIFIED) rejected.
- `an actor cannot attack itself` → same id for both.

### 5.2 New tests (add to `AttackCommandTest` or a new `CommandAddressingTest`)

- **`a command addressed to a removed unit is rejected`**: add a unit, capture
  `entityId.raw`, `world.gameObjects.remove(it)` + `world.tick()`, assert
  `issuePlayerAttack(... that id ...)` is `false`.
- **`ids stay stable when an earlier object is removed`**: add A, B, C; record ids; remove A;
  `world.tick()`; assert `world.byId(B.id) === B` and `world.byId(C.id) === C` — the exact
  case position-indexing got wrong.

### 5.3 Optional: extract + test `SET_DEST` resolution

`SET_DEST` has no direct test today (it's an inline `when` branch). Extract:
```kotlin
internal fun resolveOwnedAlive(id: Long, playerName: String, world: World,
                               players: Map<String, Player>): Alive?
```
and have the `SET_DEST` branch call it. New `SetDestCommandTest` mirrors `AttackCommandTest`'s
ownership cases. (Decision 3: this is the level-2 "component" tier; kept flat to match the repo.)

## 6. Client changes — GameGraphics (separate PR, same release)

| File | Change |
|---|---|
| `networking/NetworkClient.kt` | `setDestination(id: Long, …)`, `setSpeed(id: Long, …)`, `stopActor(id: Long)`, `attack(attackerId: Long, targetId: Long)`. Command string format unchanged. Update KDoc ("positions in the last `STATE` list" → "the `id` field of the target entry"). |
| `graphics/Window.kt` | Add `fun pickId(xPx: Double, yPx: Double): Long? = pick(xPx, yPx)?.let { lastSnapshots.getOrNull(it)?.id }`. `lastSnapshots` is `List<VisibleObjectSnapshot>` (drawable cores); `.id` survives `drawableCore()` unwrapping because the inner `VisibleObjectSnapshot` carries the same `entityId.raw` as its `Actor`/`Alive` wrapper. Keep `pick` returning an index for `Picking`'s internal use. |
| `graphics/ui/GameView.kt` | `pickActor(...): Long?`; `moveActor(actorId: Long, …)`; `attack(attackerId: Long, xPx, yPx): Boolean`. |
| `graphics/ui/Viewport.kt` | `var selectedActor: Long? = null`; set from `game.pickActor(...)`; pass through on command paths. |
| `Main.kt` `gameView()` | `pickActor` → `window.pickId`. `attack`: `val targetId = window.pickId(...) ?: return false; if (targetId == attackerId) return false; val target = client.getWorldState().firstOrNull { it.id == targetId } as? AliveSnapshot ?: return false; …` |
| `Main.kt` `buildStage()` | `selectedRaw = { viewport.selectedActor?.let { id -> client.getWorldState().firstOrNull { it.id == id } } }`. Rename `selectedIndex: () -> Int?` → `selectedId: () -> Long?`; header label `"Actor #$id"` (id is now stable and debuggable — decision 4). Update `bottomInfoPanel` signature. |
| `test/kotlin/DrawableSnapshotsTest.kt` | Add: `id` survives `drawableCore()` on all three variants; selection-by-id finds the right object after the list is reordered/shortened. |
| `test/kotlin/ViewportTest.kt` | Selection stores an id; a removed id resolves to `null` (nothing selected). |
| `README.md` | Interaction/protocol notes: commands address objects by `id`, not list position. |

## 7. Open decisions

1. **Fold in `cancelAttack()` wiring (MyGameTools#1 follow-up)?** Recommend **yes** — it's
   2 lines in the same `SET_DEST`/`STOP` branches, unblocked by 3.1.0, and matches the
   "adopt 3.1.0" theme. MyGameTools#2 (stop swinging at a dead target) needs **no**
   MyGameServer work — `Alive.considerAttack()` now ends the attack itself.
2. **`SET_SPEED` / `STOP`: switch to id-addressing or keep demo-actor-list indices?**
   Recommend **switch** — one address space for all commands is the whole point, and it lets
   a client stop/hasten any actor, not just the 10 zombies.
3. **Test package layout:** recommend **match the existing flat/default-package** three test
   files rather than introduce the `testing.component` hierarchy from the global guidelines,
   which this repo has never used. Revisit repo-wide separately if desired.
4. **GameGraphics selection label:** show the raw `id` (`"Actor #7"`) vs a friendly ordinal.
   Recommend **raw id** — stable across frames, useful for debugging.
5. **MyGameServer version bump:** `1.0.0 → 1.1.0` (minor; protocol change but a prototype,
   consistent with how the 1.6.0 wire change was treated) vs `2.0.0`. Recommend **1.1.0**.

## 8. Rollout

1. Land the plain GameTools **3.1.0 bump** first (already in the working tree: `build.gradle.kts`
   + `README.md` STATE row) — non-breaking, build green. Its own small PR.
2. Branch `feature/issue-N-entity-id-addressing` off master. Open a MyGameServer tracking
   issue ("Adopt GameTools 3.1.0 stable entity ids for command addressing"); reference
   MyGameTools#3. Squash-merge with `Closes #N` per repo convention.
3. GameGraphics: matching branch + issue, merged and released in lockstep. Neither repo's
   `master` should sit half-migrated (mixed-version = commands silently no-op).
4. Manual smoke test: two clients connected, one unit dies (drop its health to 0 with a
   `RESPAWN`→`REMOVAL` tweak or an `ATTACK`), confirm the other client's selection and a
   queued move still hit the intended unit.

## 9. Test plan (hierarchy levels)

- **L1 (gating):** `./gradlew build` + `test` green in both repos on each branch.
- **L2 (component):** rewritten `AttackCommandTest`, new id-stability tests, optional
  `SetDestCommandTest`; GameGraphics `DrawableSnapshotsTest` / `ViewportTest` additions.
- **L3 (integration):** none automated (no client/server harness); covered by §8.4 smoke test.
- **L4b (e2e):** manual — two real GameGraphics clients against a live server, unit removal
  mid-session.
