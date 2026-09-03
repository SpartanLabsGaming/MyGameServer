# MyGameServer

The authoritative server for a small real-time multiplayer game. Clients connect over UDP,
receive a continuous stream of world snapshots, and send input commands; the server runs the
simulation and is the single source of truth.

Built on **GameTools** (`io.github.spartanlaboratories:GameTools`), which provides the
game-object model (`World`, `Actor`, `Alive`, `Player`), the spatial index, and the
`GameServer` networking layer.

## Status

Prototype. Single JVM, in-memory state, one hard-coded demo world. Not hardened for
concurrency or production use.

## How it works

### Game loop

`main()` runs a fixed **60 Hz** loop:

1. **Reconcile players** — a new name in `GameServer.playerNames` gets a `Player` with a
   roster of `Alive`s dropped into the world; a name that vanished has its roster removed.
2. **`world.tick()`** — rebuilds the quadtree from current positions, then advances every
   game object one step (movement, combat, death handling).
3. **Broadcast** — every `VisibleObject` in the world is serialized and sent to every client
   as one `STATE <json>` datagram.

Client commands arrive asynchronously on `GameServer`'s listener threads and mutate world
state directly (see the concurrency note below).

### Players and ownership

Each connecting client becomes a `Player` that **owns** `ALIVES_PER_PLAYER` `Alive` actors.
Ownership is enforced on every command: a client can only move or attack with an `Alive` its
`Player` owns, and cannot attack its own units.

### The demo world

- a large tiled floor, drawn behind everything;
- three wandering "zombie" `Alive`s that belong to no one;
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
| `STATE <json>` | polymorphic array of `DrawableSnapshot` (plain / `ActorSnapshot` / `AliveSnapshot`), one entry per visible object, every tick |
| `PONG` | reply to `PING` |

### Concurrency note

Command handlers run on listener threads and mutate `Actor` / `Alive` state that the loop
thread reads and writes concurrently, without synchronization. Fine for a local demo; not
safe under real load. Tracked in
[issue #1](https://github.com/SpartanLaboratories/MyGameServer/issues/1).

## Building and running

Needs a recent JDK (23+ recommended &mdash; GameTools targets JVM 23). The Gradle wrapper
pins the build tool.

```bash
./gradlew run       # start the server (listens on UDP 9998 for handshakes)
./gradlew build     # compile + test + assemble
./gradlew test      # tests only
```

Entry point: `MainKt`.

## Tests

`src/test/kotlin/`, JUnit Platform. Current coverage: player-roster wiring
(`PlayerRosterTest`) and the `ATTACK` command's authorization matrix (`AttackCommandTest`).

Tests are intended to follow the five-level hierarchy in the global coding guidelines;
existing ones predate that layout.

## Related projects

| Project | Role |
|---|---|
| **GameGraphics** | the LWJGL desktop client that renders `STATE` and sends commands |
| **GameTools** (`io.github.spartanlaboratories:GameTools`) | game-object model, spatial index, `GameServer`; source in the sibling `MyGameTools` repo |
| **WebTools** | the UDP transport (`MultiConnectionUDPServer`) underneath `GameServer` |

## Coding rules

Paradigm, error handling (`Result` over thrown exceptions), KDoc, import grouping, and test
structure are governed by `.aiassistant/rules/`.
