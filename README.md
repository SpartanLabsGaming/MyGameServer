# MyGameServer

The authoritative server for a small real-time multiplayer game. Clients connect over UDP,
receive a continuous stream of world snapshots, and send input commands; the server runs the
simulation and is the single source of truth.

Built on **GameTools** (`io.github.spartanlabsgaming:GameTools`), which provides the
game-object model (`World`, `Actor`, `Alive`, `Player`), the spatial index, and the
`GameServer` networking layer.

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
Ownership is enforced on every command: a client can only move or attack with an `Alive` its
`Player` owns, and cannot attack its own units.

### The demo world

- a large tiled floor and, butted against its right edge, a graveyard backdrop, both drawn
  behind everything;
- ten wandering "zombie" `Alive`s that belong to no one, scattered across the graveyard;
- one "nature's prophet" `Alive` per connected player, spawned at a random anchor.

## Protocol

All traffic is UDP. Messages are verb-prefixed text, except structured mouse input which is
`INPUT <json>`. The handshake and per-connection channel setup are handled by GameTools'
`MultiConnectionUDPServer`.

### Handshake

1. Client &rarr; port **9998** (`COMMON_LISTEN_PORT`): `Iam <name> /<ip>`
2. Server &rarr; client: `/<ip> TXRXON <clientListenPort> <serverCommandPort>`
3. Both sides switch to the dedicated per-connection port pair for everything after.

### Client &rarr; server (dedicated channel)

| Command | Effect |
|---|---|
| `PING` | server replies `PONG` to that client only |
| `SET_DEST <index> <x> <y>` | move the owned `Alive` at broadcast-list slot `<index>` toward `(x, y)` |
| `ATTACK <attacker> <target>` | order the owned `Alive` at slot `<attacker>` to attack the `Alive` at slot `<target>` |
| `SET_SPEED <index> <speed>` | set a demo actor's speed (indexes the demo-actor list) |
| `STOP <index>` | stop a demo actor where it is |
| `INPUT <json>` | a `MouseAction`; a `PRESS` aims demo actor 0 at the point |

`<index>` in `SET_DEST` / `ATTACK` is a position in the last `STATE` array the client
received &mdash; the same list, in the same order, that the server resolves against.

### Server &rarr; client

| Message | Payload |
|---|---|
| `STATE <json>` | polymorphic array of `DrawableSnapshot` (plain / `ActorSnapshot` / `AliveSnapshot`), one entry per visible object, every tick; each entry also carries a `buffs` array (`BuffSnapshot`: `name`, `durationTicks`, `suppressedCapabilities`), empty when the object has no active buffs |
| `PONG` | reply to `PING` |

### Concurrency note

Command handlers never run on a `GameServer` listener thread. `onPlayerMessage` /
`onPlayerInput` only enqueue a closure onto a `ConcurrentLinkedQueue`; `drainPendingCommands`
runs every queued closure on the loop thread, first thing each iteration, before
`world.tick()`. All `Actor` / `Alive` / `World` mutation therefore happens on one thread, with
no synchronization needed. Formerly tracked in
[issue #1](https://github.com/SpartanLabsGaming/MyGameServer/issues/1) (fixed).

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
(`PlayerRosterTest`), the `ATTACK` command's authorization matrix (`AttackCommandTest`), and
the command-queue drain mechanism (`CommandQueueTest`).

Tests are intended to follow the five-level hierarchy in the global coding guidelines;
existing ones predate that layout.

## Related projects

| Project | Role |
|---|---|
| **GameGraphics** | the LWJGL desktop client that renders `STATE` and sends commands |
| **GameTools** (`io.github.spartanlabsgaming:GameTools`) | game-object model, spatial index, `GameServer`; source in the sibling `MyGameTools` repo |
| **WebTools** | the UDP transport (`MultiConnectionUDPServer`) underneath `GameServer` |

## Coding rules

Paradigm, error handling (`Result` over thrown exceptions), KDoc, import grouping, and test
structure are governed by `.aiassistant/rules/`.
