# MyGameServer

The authoritative server for a small real-time multiplayer game. Clients connect over UDP,
receive a continuous stream of world snapshots, and send input commands; the server runs the
simulation and is the single source of truth.

Built on **GameTools** (`io.github.spartanlabsgaming:gametools`, the umbrella artifact
re-exporting `gametools-core` and `gametools-net`), which provides the game-object model
(`World`, `Actor`, `Alive`, `Player`), the spatial index, and the `GameServer` networking
layer.

## Status

Prototype. Single JVM, in-memory state, one hard-coded demo world.

## How it works

### Game loop

`main()` runs a fixed **60 Hz** loop:

1. **Drain queued commands** — client commands arrive asynchronously on `GameServer`'s
   listener threads; each is queued as a closure rather than applied immediately, and this
   step runs every closure queued since the last iteration, on the loop thread (see the
   concurrency note below).
2. **Reconcile players** — a new name in `GameServer.playerNames` gets a `Player` with a
   roster of `Alive`s dropped into the world; a name that vanished has its roster removed.
3. **`world.tick()`** — rebuilds the quadtree from current positions, then advances every
   game object one step (movement, combat, death handling).
4. **Broadcast** — every `VisibleObject` in the world is serialized and sent to every client
   as one `STATE <json>` datagram.

### Players and ownership

Each connecting client becomes a `Player` that **owns** `ALIVES_PER_PLAYER` `Alive` actors.
Ownership is enforced on every command: `handleCommand` checks a decoded `ClientCommand`
against these rules — a client can only move or stop an `Alive` its `Player` owns, and can
only attack with an owned `Alive` against a target it does not own — *before* the command's
`applyTo` runs (GameTools' `applyTo` deliberately does no authorization of its own).

### The demo world

- a large tiled floor and, butted against its right edge, a graveyard backdrop, both drawn
  behind everything;
- ten wandering "zombie" `Alive`s that belong to no one, scattered across the graveyard;
- one "nature's prophet" `Alive` per connected player, spawned at a random anchor.

## Protocol

All traffic is UDP. Messages are verb-prefixed: `PING` is bare text, structured mouse input
is `INPUT <json>`, and structured orders are `COMMAND <json>` (a serialized GameTools
`ClientCommand`). The handshake and connection multiplexing are handled by GameTools'
`MultiConnectionUDPServer`.

### Handshake

1. Client &rarr; port **9998** (`COMMON_LISTEN_PORT`): `Iam <name> /<ip>`
2. Server &rarr; client: `/<ip> REGISTERED`
3. All further traffic for that player - commands, `STATE` broadcasts, `KA` keepalives - is
   multiplexed over that same shared common socket; there is no per-player dedicated port
   pair (pre-3.0.0 GameTools/WebTools handed out a `TXRXON <sendPort> <receivePort>` pair
   instead).
4. The client must send a bare `KA` datagram on an idle interval (WebTools recommends ~20s)
   from the socket it handshook on, to keep its NAT mapping warm. The server consumes `KA`
   silently; it never reaches `onPlayerMessage`/`onPlayerInput`.

### Client &rarr; server (post-handshake)

| Command | Effect |
|---|---|
| `PING` | server replies `PONG` to that client only |
| `COMMAND <json>` | a serialized `ClientCommand` (see below) |
| `INPUT <json>` | a `MouseAction`; a `PRESS` aims demo actor 0 at the point |

#### Client commands (`COMMAND <json>`)

The envelope is the verb `COMMAND`, a space, then a polymorphic JSON object with a `type`
discriminator. GameTools 5.0.0's `gametools-net` owns this wire form (`ClientCommandCodec`);
the server and the GameGraphics client build a codec the same way and must ship together.
Operands that name an object are GameTools `EntityId`s serialized as bare longs.

| `type` | Operands | Server behaviour |
|---|---|---|
| `gametools.moveTo` | `actor`, `x`, `y` | **honoured** — move the owned `Alive` `actor` toward `(x, y)`; breaks off any pending attack first |
| `gametools.attack` | `attacker`, `target` | **honoured** — order the owned `Alive` `attacker` to attack the `Alive` `target` (must not be one of the sender's own units) |
| `gametools.stop` | `actor` | **honoured** — halt the owned `Alive` `actor` where it is; breaks off any pending attack |
| `gametools.moveDir` | `actor`, `angleDegrees` | accepted but ignored — no client control yet |
| `gametools.follow` | `actor`, `target` | accepted but ignored — no client control yet |
| `gametools.stopAttack` | `alive` | accepted but ignored — no client control yet |

Example: `COMMAND {"type":"gametools.moveTo","actor":7,"x":120.0,"y":-40.0}`

Authorization (owned-unit / not-your-own-target) is applied server-side, in `handleCommand`,
*before* the command runs. Operands are resolved with `World.byId` via `ClientCommand.applyTo`,
so a command stays bound to the object the client meant even if the broadcast list has since
shifted; an unknown id, or `0` (`EntityId.UNASSIGNED`), makes the command a silent no-op.

A client that has not been updated (still sending `SET_DEST` / `ATTACK` / `STOP` text verbs,
removed in `2.0.0`) reaches only the raw path — those verbs are logged as unknown and do
nothing. Degradation is always "the command does nothing", never "wrong target".

### Server &rarr; client

| Message | Payload |
|---|---|
| `STATE <json>` | polymorphic array of `DrawableSnapshot` (plain / `ActorSnapshot` / `AliveSnapshot`), one entry per visible object, every tick; each entry carries an `id` (long &mdash; the object's stable `EntityId`, `0` if unidentified; new in GameTools 3.1.0) so a client can track an object across frames by id rather than list position, and a `buffs` array (`BuffSnapshot`: `name`, `durationTicks`, `suppressedCapabilities`), empty when the object has no active buffs |
| `PONG` | reply to `PING` |

### Concurrency note

Command handlers never run on a `GameServer` listener thread. `onPlayerMessage` /
`onPlayerInput` / `onCommand` only enqueue a closure onto a `ConcurrentLinkedQueue`;
`drainPendingCommands` runs every queued closure on the loop thread, first thing each
iteration, before `world.tick()`. This matters for `COMMAND` in particular: `applyTo` mutates
the `World`, and it must not do so while a tick is in flight. All `Actor` / `Alive` / `World`
mutation therefore happens on one thread, with no synchronization needed. Formerly tracked in
[issue #1](https://github.com/SpartanLabsGaming/MyGameServer/issues/1) (fixed).

```mermaid
sequenceDiagram
    participant C as GameGraphics client
    participant GS as GameServer (listener thread)
    participant Q as pendingCommands queue
    participant L as main() loop thread
    participant W as World

    C->>GS: UDP "COMMAND {json}"
    GS->>GS: commandCodec.decode(payload)
    alt decode ok
        GS->>Q: onCommand → add { handleCommand(name, cmd, world, players) }
    else decode fails
        GS-->>GS: log.warn, drop
    end
    Note over L: next loop iteration
    L->>Q: drainPendingCommands()
    Q->>L: handleCommand("bob", MoveTo(#7, x, y), …)
    L->>L: resolveOwnedAlive(#7, "bob") → Alive or null
    alt authorized
        L->>W: alive.cancelAttack(); command.applyTo(world)
    else not authorized
        L-->>L: silent no-op
    end
    L->>W: world.tick()
```

## Building and running

Needs a recent JDK (23+ recommended &mdash; GameTools targets JVM 23). The Gradle wrapper
pins the build tool.

```bash
./gradlew run       # start the server (listens on UDP 9998 for handshakes)
./gradlew build     # compile + test + assemble
./gradlew test      # tests only
```

Entry point: `MainKt`.

## Deployment

`master` is continuously deployed to a single Google Cloud VM: GitHub Actions
(`.github/workflows/deploy.yml`) builds and tests, then `rsync`s the
`installDist` output to the VM over SSH and restarts a `systemd` service. The
full runbook &mdash; Google Cloud firewall/IP setup, VM provisioning
(`deploy/provision-vm.sh`), the required secrets, and operating the running
server &mdash; is in [`deploy/README.md`](deploy/README.md).

## Tests

`src/test/kotlin/`, JUnit Platform. Current coverage: player-roster wiring
(`PlayerRosterTest`), the `Attack` command's authorization matrix (`AttackCommandTest`), the
`MoveTo` / `Stop` ownership predicate (`MoveCommandTest`), the `ClientCommand` dispatch and
`applyTo` wiring (`ClientCommandDispatchTest`), and the command-queue drain mechanism
(`CommandQueueTest`).

Tests are intended to follow the five-level hierarchy in the global coding guidelines;
existing ones predate that layout.

## Related projects

| Project | Role |
|---|---|
| **GameGraphics** | the LWJGL desktop client that renders `STATE` and sends commands |
| **GameTools** (`io.github.spartanlabsgaming:gametools` = `gametools-core` + `gametools-net`) | game-object model, spatial index, `GameServer`, and the `ClientCommand` / `ClientCommandCodec` command protocol (`gametools-net`); source in the sibling `MyGameTools` repo |
| **WebTools** | the UDP transport (`MultiConnectionUDPServer`) underneath `GameServer` |

## Coding rules

Paradigm, error handling (`Result` over thrown exceptions), KDoc, import grouping, and test
structure are governed by `.aiassistant/rules/`.
