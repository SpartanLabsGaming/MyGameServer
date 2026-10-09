# Plan: adopt GameTools `SimulationLoop` for the game loop

**Status:** proposed (2026-09-05)
**Depends on:** GameTools 3.1.0 (already bumped); `../6-entity-id-commands/plan.md` landed first
**Cross-repo:** none — no wire-protocol change
**Related upstream:** MyGameTools#23 (the `SimulationLoop` feature this consumes)

---

## 1. Current state

`Main.kt` `main()` hand-rolls a 60 Hz fixed-step loop:

```kotlin
val tickIntervalNanos = 1_000_000_000L / 60L
var nextTick = System.nanoTime()
while (true) {
    drainPendingCommands(pendingCommands)          // apply queued client commands
    val connected = server.playerNames             // reconcile players
    (connected - players.keys).forEach { … connectPlayer … }
    (players.keys - connected).forEach { … disconnectPlayer … }
    world.tick()                                   // advance simulation
    server.broadcast(world.gameObjects.filterIsInstance<VisibleObject>())…
    nextTick += tickIntervalNanos                  // sleep / resync
    …
}
// finally: server.shutDown()
```

Everything runs on the `main` thread; `GameServer` listener threads only enqueue onto
`pendingCommands`. That single-thread invariant is what makes the `Actor`/`World` mutation
safe without locks.

## 2. What GameTools 3.1.0 gives us

```kotlin
class SimulationLoop(
    world: World,
    settings: LoopSettings = LoopSettings(),      // tickRateHz = 20.0, maxCatchUpTicks = 5
    onTick: (tickCount: Long) -> Unit = {},
)
```

- `start()` spawns a daemon thread `gametools-sim-loop` that runs an accumulator loop:
  each pass does `world.tick(); onTick(world.tickCount)` up to `maxCatchUpTicks` times, then
  parks for `nanosPerTick / 2`. Owed time past the catch-up cap is dropped (matches the
  current "resync instead of spinning" behaviour, but bounded).
- `stop()` clears the thread ref, unparks it, joins for up to 1 s.
- `LoopSettings.tickRateHz` / `maxCatchUpTicks` are `@Volatile` and re-validated on set —
  live-tunable.

**Only hook is `onTick`, which runs *after* `world.tick()`.** There is no pre-tick hook
(see §5 decision 1).

## 3. Target shape — `Main.kt`

Extract the per-iteration work into testable functions, then hand the tick to the library:

```kotlin
/** Reconciles `players` against the names the server currently reports connected. */
internal fun reconcilePlayers(
    connected: Set<String>, players: MutableMap<String, Player>, world: World
) {
    (connected - players.keys).forEach { name -> players[name] = connectPlayer(name, world) }
    (players.keys - connected).forEach { name -> disconnectPlayer(players.remove(name)!!, world) }
}

/** One server frame's work, run on the sim-loop thread after `world.tick()`. */
internal fun serverFrame(
    world: World, server: GameServer, players: MutableMap<String, Player>,
    pendingCommands: ConcurrentLinkedQueue<() -> Unit>
) {
    drainPendingCommands(pendingCommands)
    reconcilePlayers(server.playerNames.toSet(), players, world)
    server.broadcast(world.gameObjects.filterIsInstance<VisibleObject>())
        .onFailure { cause -> println("Failed to broadcast actor state: ${cause.message}") }
}
```

`main()` becomes:

```kotlin
val world = World(/* seed = … optional, see decision 3 */).apply { add(floor); add(graveyard); actors.forEach(::add) }
val players = mutableMapOf<String, Player>()
val pendingCommands = ConcurrentLinkedQueue<() -> Unit>()
var serverRef: GameServer? = null
val server = GameServer(maxConnections = 4, onPlayerMessage = { … enqueue … }, onPlayerInput = { … enqueue … })
serverRef = server

val loop = SimulationLoop(
    world = world,
    settings = LoopSettings(tickRateHz = 60.0),
    onTick = {
        runCatching { serverFrame(world, server, players, pendingCommands) }
            .onFailure { cause -> println("Server frame failed: ${cause.message}") }
    },
)

Runtime.getRuntime().addShutdownHook(Thread {
    loop.stop()
    server.shutDown().onFailure { cause -> println("Failed to shut down cleanly: ${cause.message}") }
})

loop.start().onFailure { cause -> println("Could not start the simulation loop: ${cause.message}"); return }
CountDownLatch(1).await()   // park main; the daemon loop + shutdown hook do the work
```

### Behavioural note: command latency is unchanged

Today: `drain → tick → broadcast` per iteration. A command that arrives during tick *N* is
drained at the top of iteration *N+1* and applied by tick *N+1*.

After: `tick → onTick(drain + reconcile + broadcast)`. A command that arrives during tick
*N* is drained in `onTick` after tick *N* and applied by tick *N+1*. **Same one-tick
latency.** Broadcast still goes out once per tick, right after the tick. Reconciliation
(connect/disconnect) also shifts to post-tick — a newly connected player's units first
appear in the broadcast one tick later than today; immaterial at 60 Hz.

## 4. Thread-safety

`onTick` runs on `gametools-sim-loop`, so `serverFrame` (and therefore every
`drainPendingCommands` / `connectPlayer` / `disconnectPlayer` / `world` mutation) runs on
that one thread. `GameServer` callbacks still only `pendingCommands.add { … }`. The
single-writer invariant holds — only the thread's *name* changes.

**Update the KDoc** in `connectPlayer`, `disconnectPlayer`, `handleClientMessage`,
`handleClientInput`, `drainPendingCommands` that currently say "the main loop thread" / "the
loop thread in `main()`" → "the simulation-loop thread (`SimulationLoop`'s `onTick`)".

## 5. Open decisions

1. **Pre-tick work with only a post-tick hook.** Recommended: **accept it** — run
   drain/reconcile/broadcast in `onTick` (post-tick); latency is identical (§3). Alternative:
   file a MyGameTools issue for a `beforeTick` / `onBeforeTick` hook on `SimulationLoop`
   (fits the "Phase 0" feature line of #21–23). Not blocking; do it only if a real ordering
   need appears.
2. **Keep `serverFrame` broadcasting inside `onTick`, or move broadcast to a separate
   cadence?** Recommended: **keep it per-tick** (unchanged from today). A slower broadcast
   rate is a separate optimisation.
3. **Use `World(seed = …)` for a fixed seed?** Recommended: **no for now** — let it default
   to a fresh logged value. Revisit if/when a replay or deterministic-test need appears
   (that's really a separate "record & replay" feature).
4. **`maxCatchUpTicks`:** default `5` is fine (was effectively unbounded-then-resync).
   Leave default.
5. **Parking `main`:** `CountDownLatch(1).await()` vs `Thread.currentThread().join()` vs a
   simple `while (loop.isRunning) Thread.sleep(1000)`. Recommended: **`CountDownLatch`**,
   counted down by the shutdown hook, so `main` returns cleanly on SIGINT.

## 6. Tests — `src/test/kotlin/`

Flat / default-package, matching the repo.

- **New `PlayerReconciliationTest`** (level 2 — this logic is inline and untested today):
  - a name that appears gets a `Player` with `ALIVES_PER_PLAYER` units added to the world.
  - a name that vanishes has its units removed from `world.gameObjects` and the map entry
    dropped.
  - a name present in both sets is left alone (no churn).
- **`CommandQueueTest`** — unchanged (still covers `drainPendingCommands`).
- **`serverFrame`** — optionally a smoke test with a fake/no-op `GameServer` double if one is
  cheap to stand up; otherwise covered by the components above + the §7 manual check.
- `SimulationLoop`'s timing/accumulator behaviour is the library's own responsibility — do
  **not** re-test it here.

## 7. Risks

| Risk | Mitigation |
|---|---|
| An exception in `onTick` kills the daemon loop silently (no try/catch around `onTick` in `SimulationLoop.advance`). | Wrap the `serverFrame` call in `runCatching { … }.onFailure { print }` (shown in §3). Keep `broadcast().onFailure {}` too. |
| `main` returns while the daemon loop is alive → JVM exits mid-tick. | Park `main` on a latch; shutdown hook stops the loop then the server. |
| `SimulationLoop.stop()` join timeout is 1 s — a wedged tick delays exit by ≤1 s. | Acceptable; note it. |
| Connect/disconnect now post-tick — a connecting player's units appear one tick later. | Immaterial at 60 Hz; documented in §3. |
| Tick rate default is 20 Hz, not 60. | Pass `LoopSettings(tickRateHz = 60.0)` explicitly. |

## 8. Rollout

1. Rebase on `master` after `../6-entity-id-commands/plan.md` has landed (both edit `Main.kt`).
2. Branch `refactor/issue-N-simulation-loop`. Open a MyGameServer tracking issue.
3. `./gradlew build` + `run` a local server, connect one GameGraphics client, verify units
   tick, move commands land, a client disconnect removes units, Ctrl-C shuts down cleanly.
4. Squash-merge with `Closes #N` per repo convention. No version bump strictly required
   (internal refactor, no external shape change) — but bump the patch version if a release
   is cut, and note "loop now `SimulationLoop`-backed" in the README's architecture line.

## 9. Test plan (hierarchy levels)

- **L1 (gating):** `./gradlew build` + `test` green.
- **L2 (component):** new `PlayerReconciliationTest`; existing `CommandQueueTest`,
  `AttackCommandTest`, `PlayerRosterTest` still green.
- **L3 (integration):** none automated.
- **L4b (e2e):** manual — live server + client, connect / command / disconnect / clean
  shutdown (§8.3).
