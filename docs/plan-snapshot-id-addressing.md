# Plan: adopt GameTools 5.0.0's `ClientCommand` protocol

**Status:** re-scoped 2026-09-07. An interim, hand-rolled entity-id command protocol landed
on `master` (see "Interim state" below); the remaining work is to replace it with the
first-class command API that GameTools **5.0.0** now ships. Tracked by MyGameServer#6.
**Depends on:** GameTools 5.0.0 (`io.github.spartanlabsgaming:gametools`, already bumped)
**Closes (downstream):** MyGameTools#3 — "Broadcast snapshots have no stable identity"
**Cross-repo:** GameGraphics must ship the matching client change in lockstep — mixed
versions degrade to "commands no-op", never "wrong target", but neither `master` may sit
half-migrated.
**Order:** do this before `plan-simulation-loop-adoption.md` (both touch `Main.kt`).

---

## Interim state (already on `master`)

The first pass at MyGameServer#6 was written against GameTools **3.1.0**, before the library
had a command layer, so it hand-rolls one in `Main.kt`:

- `SET_DEST <id> <x> <y>` / `ATTACK <atkId> <tgtId>` / `STOP <id>` — whitespace-delimited
  text verbs, operands are `EntityId.raw` values, resolved with `World.byId`.
- `SET_SPEED` was **removed entirely** (2026-09-07) — it was an unauthorized demo hook with
  no client equivalent worth keeping, and it has no standard-command counterpart.
- Ownership / "not your own target" checks live in `Main.kt`
  (`resolveOwnedAlive`, `issuePlayerAttack`), covered by `AttackCommandTest` /
  `SetDestCommandTest`.
- `Alive.cancelAttack()` is called before a `SET_DEST` / `STOP` so a fresh move order breaks
  off a pending attack.

This is correct but is a private re-implementation of something the library now owns and
versions.

## What GameTools 5.0.0 gives us

`gametools-core`, package `com.spartanlabs.gaming.networking.command`:

| Piece | What it is |
|---|---|
| `interface ClientCommand` + 6 standard commands | `MoveTo(actor, x, y)`, `MoveDir(actor, angleDegrees)`, `Follow(actor, target)`, `Stop(actor)`, `Attack(attacker, target)`, `StopAttack(alive)` — all `@Serializable`, `EntityId` operands, `@SerialName("gametools.*")` |
| `ClientCommandCodec(appCommands = EmptySerializersModule())` | polymorphic `COMMAND <json>` envelope (same shape as `STATE <json>` / `INPUT <json>`, `type` discriminator). `encode(cmd): String`, `decode(payloadAfterVerb): Result<ClientCommand>` |
| `ClientCommand.applyTo(world): ApplyResult` | resolves every `EntityId` operand via `World.byId`, checks the resolved object is the right kind (`Actor` vs `Alive`), then runs the mechanism. Returns `Applied` / `TargetMissing(id)` / `WrongType(id, expected)` / `Unhandled`. **Does not authorize** — ownership/faction/range is the caller's job, by design. |
| `GameServer(…, commandCodec: ClientCommandCodec?, onCommand: (playerName, ClientCommand) -> Unit)` | new `@JvmOverloads` ctor params, both defaulted. Routes a `COMMAND <json>` datagram to `onCommand`; with no codec a `COMMAND` datagram falls through to `onPlayerMessage` unchanged. |

`EntityId` is now `@Serializable(with = EntityIdSerializer::class)` and serializes as a **bare
`Long`**, so the id a client reads off a `STATE` entry drops straight into a command payload.

## Wire protocol change

The text verbs (`SET_DEST …`, `ATTACK …`, `STOP …`) are replaced by one verb:

```
COMMAND {"type":"gametools.moveTo","actor":7,"x":120.0,"y":-40.0}
COMMAND {"type":"gametools.attack","attacker":7,"target":13}
COMMAND {"type":"gametools.stop","actor":7}
```

`PING`/`PONG` stays on the raw `onPlayerMessage` path. `INPUT <json>` (mouse) is unchanged.

**Mixed-version failure mode:** an old client still sends `SET_DEST 7 …` text → the new
server has a `commandCodec`, so `SET_DEST` is an unrecognised verb → `onPlayerMessage` logs
"Unknown command". A new client sends `COMMAND <json>` to an old server → old server has no
`COMMAND` verb → also ignored. No wrong-target window. The two repos still ship together.

## Server changes — `src/main/kotlin/Main.kt`

1. **Add a shared codec.** `private val COMMAND_CODEC = ClientCommandCodec()` (no app module —
   the six standard commands are enough now `SET_SPEED` is gone). If GameGraphics and
   MyGameServer ever need a custom command, both build the codec from the same
   `SerializersModule`.
2. **Wire it into `GameServer`:**
   ```kotlin
   val server = GameServer(
       maxConnections = 4,
       onPlayerMessage = { name, msg -> pendingCommands.add { serverRef?.let { handlePlainMessage(name, msg, it) } } },
       onPlayerInput   = { name, input -> pendingCommands.add { handleClientInput(name, input, actors) } },
       commandCodec = COMMAND_CODEC,
       onCommand = { name, cmd -> pendingCommands.add { handleCommand(name, cmd, world, players) } },
   )
   ```
   Same "queue onto the loop thread" discipline as today — `applyTo` mutates `World` state,
   so it must not run on a listener thread.
3. **`handlePlainMessage`** shrinks to just `PING` → `server.push(name, "PONG")` plus an
   "unknown command" log. Delete the `SET_DEST` / `ATTACK` / `STOP` parsing, the
   `parts.split` / `toLongOrNull` operand handling, and the `server` param threading that only
   `PING` still needs (keep it — `PONG` needs `push`).
4. **New `handleCommand(playerName, command, world, players)`** — authorization the library
   omits, then delegate:
   ```kotlin
   when (command) {
       is MoveTo -> onOwnedAlive(command.actor) { it.cancelAttack(); command.applyTo(world) }
       is Stop   -> onOwnedAlive(command.actor) { it.cancelAttack(); command.applyTo(world) }
       is Attack -> {
           val attacker = resolveOwnedAlive(command.attacker.raw, playerName, world, players) ?: return
           val target = world.byId(command.target) as? Alive ?: return
           if (attacker === target || target.owner === players[playerName]) return
           command.applyTo(world)     // == attacker.issueAttack(target)
       }
       is MoveDir, is Follow, is StopAttack -> { /* not exposed to clients yet — ignore */ }
   }
   ```
   where `onOwnedAlive(id) { … }` is `resolveOwnedAlive(id.raw, playerName, world, players)?.let { … }`.
   - Keeping `cancelAttack()` before `MoveTo`/`Stop` is a **deliberate local policy** —
     `applyTo` will not do it (see MyGameTools#<TBD>). If that upstream issue lands, this
     collapses to a plain `command.applyTo(world)`.
   - `resolveOwnedAlive` / `issuePlayerAttack`'s ownership logic is reused; `issuePlayerAttack`
     can fold into the `is Attack` branch or stay as the tested helper it delegates to.
5. **`ApplyResult` handling:** log anything that is not `Applied` at debug (`TargetMissing`
   when the unit died between pick and act is normal). Not surfaced to the client in this
   pass.

## Server tests — `src/test/kotlin/`

Keep the flat / default-package layout the existing test files use.

- **`AttackCommandTest`** — the ownership assertions move to driving `handleCommand(name,
  Attack(EntityId(a), EntityId(b)), …)` (or keep calling `issuePlayerAttack` if it stays the
  helper). Id-stability tests (`ids stay stable when an earlier object is removed`) are
  unchanged.
- **`SetDestCommandTest`** — `resolveOwnedAlive` cases unchanged; add a `handleCommand` +
  `MoveTo` case asserting `cancelAttack()` ran (unit was attacking → issue a `MoveTo` → its
  `attackState` is back to `NONE` and `destination` moved).
- **New `CommandCodecTest`** (level-2 / deterministic): `COMMAND_CODEC.decode(codec.encode(cmd)
  .substringAfter(' '))` round-trips each of the six commands; a garbage payload yields
  `Result.failure`; an unknown `type` yields `Result.failure`.
- **New in `Main.kt` handling:** `MoveDir` / `Follow` / `StopAttack` from a client are
  ignored (no exception, no state change).

## Client changes — GameGraphics (separate PR, same release)

| File | Change |
|---|---|
| `build.gradle*` | bump to `io.github.spartanlabsgaming:gametools:5.0.0` (or `gametools-core` alone if it only needs the snapshot + command types). |
| `networking/NetworkClient.kt` | replace the text-command builders (`setDestination`, `stopActor`, `attack`) with `send(codec.encode(MoveTo(EntityId(id), x, y)))` etc., using a `ClientCommandCodec` built the same way as the server's. Drop `setSpeed` entirely. |
| snapshot decoding | `DrawableSnapshot.id` is now `EntityId`, not `Long` — update any reader to `.id.raw` (or keep `EntityId`). Wire bytes are unchanged (`EntityIdSerializer` emits a bare long), so `ignoreUnknownKeys` decoders are unaffected at runtime; this is a source-level fix only. |
| `graphics/**`, `Main.kt` | selection already stores an id (from the interim pass); point the command paths at the codec. |
| `test/kotlin/**` | codec round-trip; `id` survives snapshot unwrapping as `EntityId`. |
| `README.md` | protocol section: `COMMAND <json>` envelope + the six `gametools.*` commands. |

## Version bump

`1.0.0 → 2.0.0`. The command wire form is fully replaced (`COMMAND <json>` for the text
verbs), which is a larger break than the interim pass's operand-semantics change; a major
bump is clearer than another `1.x`.

## Open decisions

1. **`MoveDir` / `Follow` / `StopAttack` — expose to clients now or later?** Recommend
   **later** — GameGraphics has no UI for "move in a heading" or "follow that unit" yet, and
   `handleCommand` ignoring them is a one-liner. Add when the client grows the controls.
2. **Keep `issuePlayerAttack` / `resolveOwnedAlive` as separate tested helpers, or inline into
   `handleCommand`?** Recommend **keep** — they are the authorization unit and are already
   tested; `handleCommand` just calls them then `applyTo`.
3. **Surface `ApplyResult` failures to the client?** Recommend **no** for this pass — nothing
   consumes it and `TargetMissing` is routine. Revisit if a client wants command NACKs.
4. **Should `MoveTo`/`Stop` cancelling a pending attack be an upstream feature?** Filed as
   MyGameTools#<TBD> (see below). Until then MyGameServer keeps the two-line local policy.

## Rollout

1. This plan's MyGameServer branch: `Main.kt` + tests + README + this doc. `./gradlew build`
   green. Squash-merge per repo convention.
2. GameGraphics: matching branch, merged and released in lockstep. Neither `master`
   half-migrated.
3. Manual smoke test: two clients connected, issue a move + an attack from each, kill one
   unit (`ATTACK` to 0 HP → `REMOVAL`), confirm the survivor's queued command still hits the
   intended unit and a command naming the dead unit is a silent no-op.

## Test plan (hierarchy levels)

- **L1 (gating):** `./gradlew build` + `test` green in both repos on each branch.
- **L2 (component):** `handleCommand` ownership + `cancelAttack` behaviour; `CommandCodecTest`
  round-trips; ignored-command cases.
- **L3 (integration):** none automated (no client/server harness); covered by the §Rollout
  smoke test.
- **L4b (e2e):** manual — two real GameGraphics clients against a live server, unit removal
  mid-session.
