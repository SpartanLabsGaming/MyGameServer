import com.spartanlabs.gaming.gameobjects.Actor
import com.spartanlabs.gaming.gameobjects.Alive
import com.spartanlabs.gaming.gameobjects.EntityId
import com.spartanlabs.gaming.gameobjects.ModularStat
import com.spartanlabs.gaming.gameobjects.Player
import com.spartanlabs.gaming.gameobjects.VisibleObject
import com.spartanlabs.gaming.gameobjects.World
import com.spartanlabs.gaming.networking.GameServer
import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.gaming.networking.command.ApplyResult
import com.spartanlabs.gaming.networking.command.Attack
import com.spartanlabs.gaming.networking.command.ClientCommand
import com.spartanlabs.gaming.networking.command.ClientCommandCodec
import com.spartanlabs.gaming.networking.command.Follow
import com.spartanlabs.gaming.networking.command.MoveDir
import com.spartanlabs.gaming.networking.command.MoveTo
import com.spartanlabs.gaming.networking.command.Stop
import com.spartanlabs.gaming.networking.command.StopAttack
import com.spartanlabs.gaming.networking.command.applyTo
import com.spartanlabs.generaltools.Color
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt
import kotlin.random.Random


/** How many [Alive] actors each connecting [Player] is given. */
internal const val ALIVES_PER_PLAYER = 1

/**
 * Builds a game-domain [Player] for the just-connected client [name] with
 * [ALIVES_PER_PLAYER] freshly created [Alive] actors it [Player.own]s, and adds those
 * actors to [world] so they are ticked, indexed and broadcast from the next frame on.
 *
 * The roster is dropped at a random anchor so multiple players' units do not pile up, and
 * each actor is sent walking so the connection is visible in a client immediately.
 *
 * Called from the game loop (see the reconciliation in [main]), never from a listener
 * thread, so mutating [world] here needs no synchronisation.
 */
internal fun connectPlayer(name: String, world: World): Player {
    val player = Player(name)
    val anchorX = Random.nextDouble(-150.0, 150.0)
    val anchorY = Random.nextDouble(-150.0, 150.0)
    repeat(ALIVES_PER_PLAYER) { i ->
        val startX = anchorX + i * 30.0
        val alive = Alive(Point(x = startX, y = anchorY), Dimensions(width = 155.0, height = 155.0), 400.0).apply {
            texture = "natures prophet.png"
            turns = false
            // Actor.speed is a ModularStat since GameTools 1.8.0 (baseSpeed is gone). Setting
            // ModularStat.base alone doesn't recompute the effective value, so replace the
            // whole stat - there are no speed mods in this demo to preserve.
            speed = ModularStat(base = 30.0)
            destination = Point(x = startX, y = anchorY + 120.0)
        }
        player.own(alive) // sets alive.owner and adds to the roster (kept in step since 1.5.2)
        world.add(alive)  // World.add (1.6.0) also sets alive.world, needed for death handling
    }
    println("'$name' joined; gave it ${player.ownedAlives.size} Alive(s) near (${anchorX.roundToInt()}, ${anchorY.roundToInt()})")
    return player
}

/** Removes [player]'s owned actors from [world] so they stop being ticked and broadcast. */
internal fun disconnectPlayer(player: Player, world: World) {
    // Snapshot first: clearing owner also removes the actor from player.ownedAlives (1.5.2),
    // which is a live view of the roster, so iterating it directly would fail mid-loop.
    val roster = player.ownedAlives.toList()
    roster.forEach { it.owner = null; it.world = null }
    world.gameObjects.removeAll(roster.toSet())
    println("'${player.name}' left; removed ${roster.size} owned Alive(s)")
}

/**
 * Handles a raw, unstructured client datagram - anything that is not an `INPUT <json>` mouse
 * event (see [handleClientInput]) or a `COMMAND <json>` client command (see [handleCommand]).
 * Dispatches on the first whitespace-delimited token, case-insensitively:
 *   PING -> replies "PONG" to just that player via [GameServer.push]
 *
 * Structured orders (move / attack / stop) used to arrive here as text verbs; since GameTools
 * 5.0.0 they come in as `COMMAND <json>` ([ClientCommand]s) and are routed to [handleCommand].
 * `PING` is the only verb still spoken on this raw path.
 *
 * GameServer's onPlayerMessage callback provides the sending player's name, so - unlike a
 * plain broadcast-only server - PING can reply to just that one player.
 *
 * Called only from [drainPendingCommands] on the main loop thread - never directly from a
 * GameServer listener thread.
 */
private fun handlePlainMessage(
    playerName: String,
    message: String,
    server: GameServer
) {
    when (message.split(" ".toRegex()).firstOrNull { it.isNotBlank() }?.uppercase()) {
        "PING" -> server.push(playerName, "PONG")
            .onFailure { cause -> println("Could not reply to '$playerName': ${cause.message}") }

        else -> println("Unknown message from '$playerName': $message")
    }
}

/**
 * Authorizes a decoded [ClientCommand] from [playerName] against this game's ownership rules -
 * which GameTools' [applyTo] deliberately omits - then carries it out with [applyTo].
 *
 * Only the three commands a client has UI for are honoured; each is gated first:
 *   [MoveTo], [Stop] -> the operand must be an [Alive] the sender owns ([resolveOwnedAlive]).
 *     [Alive.cancelAttack] is called first so a fresh move order breaks off a pending attack
 *     (a local policy until MyGameTools#39 folds it into [applyTo]).
 *   [Attack] -> the attacker must be owned by the sender and the target a different [Alive]
 *     the sender does not own ([authorizeAttack]).
 * [MoveDir], [Follow] and [StopAttack] are valid [ClientCommand]s with no client control yet;
 * they are logged and dropped. [ClientCommand] is not `sealed`, so a future standard command
 * also falls through the `else` branch until wired here.
 *
 * Called only from [drainPendingCommands] on the main loop thread (see the queueing in
 * [main]), so [applyTo]'s [World] mutation never races [main]'s `world.tick()`.
 */
internal fun handleCommand(
    playerName: String,
    command: ClientCommand,
    world: World,
    players: Map<String, Player>
) {
    when (command) {
        is MoveTo -> resolveOwnedAlive(command.actor, playerName, world, players)?.let { alive ->
            alive.cancelAttack() // local policy - a fresh move order breaks off an attack (MyGameTools#39)
            report(command, command.applyTo(world), playerName)
        }

        is Stop -> resolveOwnedAlive(command.actor, playerName, world, players)?.let { alive ->
            alive.cancelAttack()
            report(command, command.applyTo(world), playerName)
        }

        is Attack -> if (authorizeAttack(playerName, command.attacker, command.target, world, players)) {
            report(command, command.applyTo(world), playerName)
        }

        is MoveDir, is Follow, is StopAttack ->
            println("Ignoring unsupported command ${command::class.simpleName} from '$playerName'")

        else ->
            println("Ignoring unknown command ${command::class.simpleName} from '$playerName'")
    }
}

/**
 * Logs an [applyTo] outcome that is not [ApplyResult.Applied]. Those are expected and dropped:
 * a [ApplyResult.TargetMissing] is routine when a unit died between the client's click and the
 * command being drained. Nothing is surfaced to the client this pass.
 */
private fun report(command: ClientCommand, result: ApplyResult, playerName: String) {
    if (result !is ApplyResult.Applied) {
        println("Command ${command::class.simpleName} from '$playerName' did not apply: $result")
    }
}

/**
 * Resolves [id] to an [Alive] the client [playerName] is allowed to drive: the id must name
 * an object [World.byId] still owns, that object must be an [Alive], and its [Alive.owner]
 * must be the [Player] the sending client is associated with.
 *
 * [id] is a GameTools [EntityId] carried inside a [ClientCommand]'s JSON (see [handleCommand]),
 * resolved with [World.byId] so the command stays bound to the object the client meant even if
 * the broadcast list has since shifted.
 *
 * @return the [Alive], or `null` when the id is unknown/removed, names a non-[Alive], or
 * names a unit the sender does not own - all of which a caller treats as "ignore the command"
 */
internal fun resolveOwnedAlive(
    id: EntityId,
    playerName: String,
    world: World,
    players: Map<String, Player>
): Alive? {
    val alive = world.byId(id) as? Alive ?: return null
    val owner = alive.owner ?: return null
    return alive.takeIf { owner === players[playerName] }
}

/**
 * Whether [playerName] may order the [Alive] with entity id [attackerId] to attack the [Alive]
 * with entity id [targetId]. This is the authorization GameTools' [applyTo] deliberately omits;
 * [handleCommand] calls it before letting an [Attack] command's [applyTo] run.
 *
 * Both operands are GameTools [EntityId]s carried inside the [Attack] command's JSON; they are
 * resolved with [World.byId], so the check stays bound to the objects the client meant even if
 * the broadcast list has since shifted. Every condition must hold: both ids resolve to an
 * [Alive], they are not the same actor, the attacker's [Alive.owner] is the sending [Player],
 * and the target is not one of that same player's own units.
 *
 * @return `true` when this player may issue this attack, `false` when the order is rejected
 */
internal fun authorizeAttack(
    playerName: String,
    attackerId: EntityId,
    targetId: EntityId,
    world: World,
    players: Map<String, Player>
): Boolean {
    val attacker = world.byId(attackerId) as? Alive ?: return false
    val target = world.byId(targetId) as? Alive ?: return false
    if (attacker === target) return false

    val player = players[playerName] ?: return false
    return attacker.owner === player && target.owner !== player
}

/**
 * Handles a structured mouse event delivered by a client as an `INPUT <json>` datagram.
 * GameServer 1.2.0 decodes these into [MouseAction]s and routes them here, separately from
 * both the raw messages that reach [handlePlainMessage] and the [ClientCommand]s that reach
 * [handleCommand].
 *
 *   PRESS   -> aims demo actor 0 (the first zombie) at the clicked point
 *   MOVE    -> ignored (cursor tracking is not modelled in this demo)
 *   RELEASE -> ignored
 *
 * Unlike an id-addressed [MoveTo] command, this path is positional by design - it always
 * drives `actors[0]`, a fixed demo hook with no ownership check. Coordinates arrive in the
 * client's window pixel space (origin top-left) and are used here as world coordinates
 * unchanged.
 *
 * Like [handleCommand], this is called only from [drainPendingCommands] on the main loop
 * thread, never directly from a GameServer listener thread.
 */
private fun handleClientInput(
    playerName: String,
    input: MouseAction,
    actors: List<Actor>
) {
    when (input.type) {
        MouseActionType.PRESS -> actors.firstOrNull()?.let { actor ->
            actor.destination = Point(x = input.x, y = input.y)
            println("'$playerName' pressed button ${input.button} at (${input.x}, ${input.y}); actor 0 now heading there")
        }

        MouseActionType.MOVE, MouseActionType.RELEASE ->
            println("Ignoring ${input.type} input from '$playerName' at (${input.x}, ${input.y})")
    }
}

/**
 * Runs every closure queued in [queue], in FIFO order, removing each as it runs.
 *
 * [main] calls this once per loop iteration, before `world.tick()`, to apply commands that
 * [GameServer]'s listener thread(s) queued via [main]'s `onPlayerMessage`/`onPlayerInput`/
 * `onCommand` callbacks - so [handlePlainMessage], [handleCommand] and [handleClientInput]
 * always execute on the loop thread, never on a listener thread, with no synchronisation
 * needed against the tick.
 */
internal fun drainPendingCommands(queue: ConcurrentLinkedQueue<() -> Unit>) {
    generateSequence(queue::poll).forEach { it() }
}

/** How many unowned "zombie" [Alive]s the demo spawns in the graveyard. */
private const val ZOMBIE_COUNT = 10

fun main() {
    // Two decorative backdrops with no behaviour of their own, added to the world first so
    // clients draw them behind everything else. The floor is 6000x6000 centred on the origin
    // (right edge at x = 3000); the graveyard is butted against that edge.
    val floor = VisibleObject(width = 6_000.0, height = 6_000.0, x = 0.0, y = 0.0).apply {
        texture = "whitetilefloor.jpg"
    }
    val graveyard = VisibleObject(width = 3_000.0, height = 6_000.0, x = 4_500.0, y = 0.0).apply {
        texture = "graveyard.jpg"
    }

    // A random point inside the graveyard, kept clear of its edges so a spawned actor is
    // visibly on the texture.
    val graveyardInset = 250.0
    fun randomGraveyardPoint() = Point(
        x = Random.nextDouble(
            graveyard.location.x - graveyard.dimensions.width / 2 + graveyardInset,
            graveyard.location.x + graveyard.dimensions.width / 2 - graveyardInset,
        ),
        y = Random.nextDouble(
            graveyard.location.y - graveyard.dimensions.height / 2 + graveyardInset,
            graveyard.location.y + graveyard.dimensions.height / 2 - graveyardInset,
        ),
    )

    // ZOMBIE_COUNT unowned "zombie" Alives scattered across the graveyard, each ambling to a
    // random spot within it at a random pace. turns = false: they are drawn upright, so their
    // facing angle is not meaningful to a renderer even though they still track one internally.
    val actors = List(ZOMBIE_COUNT) {
        Alive(randomGraveyardPoint(), Dimensions(width = 120.0, height = 120.0), 100.0).apply {
            destination = randomGraveyardPoint()
            speed = ModularStat(base = Random.nextDouble(5.0, 20.0))
            texture = "zombie.png"
            turns = false
        }
    }

    // GameTools' World: owns the game objects and a quadtree it rebuilds from their positions
    // at the start of every tick(). World.add (1.6.0) also wires an Alive's back-reference to
    // the world so a death can queue it for removal; anything created later just needs the
    // same call to be ticked, indexed, and broadcast.
    val world = World().apply {
        add(floor)
        add(graveyard)
        actors.forEach { add(it) }
    }

    // GameServer exposes no connect/disconnect callback to game code, so the loop reconciles
    // this map against server.playerNames each frame: a name that appeared gets a Player with
    // a roster of Alives, a name that vanished has its roster removed from the world. A plain
    // map suffices - both the reconciliation below and the message handlers that read it (via
    // pendingCommands, drained by drainPendingCommands) run on the loop thread only.
    val players = mutableMapOf<String, Player>()

    // Commands parsed from GameServer's listener thread(s) - onPlayerMessage/onPlayerInput/
    // onCommand below - are queued here rather than applied immediately, and drained on the
    // loop thread by drainPendingCommands before world.tick(). That keeps every Actor/World
    // mutation on one thread, present and future commands alike.
    val pendingCommands = ConcurrentLinkedQueue<() -> Unit>()

    // The codec that decodes a "COMMAND <json>" datagram into a ClientCommand (GameTools
    // 5.0.0). No app module: the six standard gametools.* commands are the whole vocabulary.
    // A GameGraphics client must encode with a codec built the same way.
    val commandCodec = ClientCommandCodec()

    // GameServer's callbacks are constructor parameters with no public setter,
    // but handlePlainMessage needs a reference to the server itself (to reply
    // via push()). serverRef sidesteps the chicken-and-egg problem: the lambda
    // only reads it once a message actually arrives, by which point the
    // assignment below has long since happened.
    //
    // Every callback is passed by name: GameServer's constructor has grown trailing
    // parameters (onPlayerInput in 1.2.0, commandCodec + onCommand in 5.0.0), so a trailing
    // lambda would bind to whichever is last rather than to onPlayerMessage.
    var serverRef: GameServer? = null
    val server = GameServer(
        maxConnections = 4,
        onPlayerMessage = { playerName, message ->
            pendingCommands.add {
                serverRef?.let { handlePlainMessage(playerName, message, it) }
            }
        },
        onPlayerInput = { playerName, input ->
            pendingCommands.add { handleClientInput(playerName, input, actors) }
        },
        commandCodec = commandCodec,
        onCommand = { playerName, command ->
            pendingCommands.add { handleCommand(playerName, command, world, players) }
        }
    )
    serverRef = server

    val tickIntervalNanos = 1_000_000_000L / 60L // 60 times per second
    var nextTick = System.nanoTime()

    try {
        while (true) {
            drainPendingCommands(pendingCommands) // apply queued client commands on this thread

            val connected = server.playerNames
            (connected - players.keys).forEach { name -> players[name] = connectPlayer(name, world) }
            (players.keys - connected).forEach { name -> disconnectPlayer(players.remove(name)!!, world) }

            world.tick() // rebuilds the quadtree, then advances every object one step

            // Sends every VisibleObject in the world as a single "STATE <json>" datagram to
            // every player. Each entry carries its stable EntityId, which is what a client
            // names in a COMMAND operand (resolved server-side via World.byId).
            server.broadcast(world.gameObjects.filterIsInstance<VisibleObject>())
                .onFailure { cause -> println("Failed to broadcast actor state: ${cause.message}") }

            nextTick += tickIntervalNanos
            val sleepNanos = nextTick - System.nanoTime()
            if (sleepNanos > 0) {
                Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            } else {
                // Fell behind (e.g. a slow tick) - resync instead of spinning to catch up.
                nextTick = System.nanoTime()
            }
        }
    } finally {
        server.shutDown()
            .onFailure { cause -> println("Failed to shut down cleanly: ${cause.message}") }
    }
}