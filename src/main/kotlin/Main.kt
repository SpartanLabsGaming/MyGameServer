import com.spartanlabs.gaming.gameobjects.Actor
import com.spartanlabs.gaming.gameobjects.Alive
import com.spartanlabs.gaming.gameobjects.ModularStat
import com.spartanlabs.gaming.gameobjects.Player
import com.spartanlabs.gaming.gameobjects.VisibleObject
import com.spartanlabs.gaming.gameobjects.World
import com.spartanlabs.gaming.networking.GameServer
import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.generaltools.Color
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import java.util.concurrent.ConcurrentHashMap
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
 * Parses a raw client message (whitespace-separated, case-insensitive
 * command) and dispatches on the first token:
 *   PING                          -> replies "PONG" to just that player
 *   SET_DEST <index> <x> <y>      -> sets the destination of the index-th broadcast object
 *   SET_SPEED <index> <speed>     -> sets actors[index].speed
 *   STOP <index>                  -> sets destination to current location
 *   ATTACK <attacker> <target>    -> orders the attacker Alive to attack the target Alive
 *
 * SET_DEST's and ATTACK's indices are positions in the broadcast list - the [VisibleObject]s
 * among [World.gameObjects], in insertion order - which is the same list, in the same order,
 * that the client picked against in the last `STATE` it received. SET_DEST is applied only
 * when that slot holds an [Alive], that [Alive] has an [Alive.owner], and that owner is the
 * [Player] the sending client is associated with - else it is ignored. ATTACK additionally
 * requires the target slot to hold a different [Alive] that is not one of the sending
 * player's own units (see [issuePlayerAttack]).
 *
 * GameServer's onPlayerMessage callback provides the sending player's name,
 * so - unlike a plain broadcast-only server - PING can reply to just that
 * one player via [GameServer.push].
 *
 * Note: this mutates Actor state from GameServer's listener thread(s) while
 * the main loop ticks/reads that same state - fine for a simple demo, but
 * not hardened against concurrent access.
 */
private fun handleClientMessage(
    playerName: String,
    message: String,
    actors: List<Actor>,
    world: World,
    players: Map<String, Player>,
    server: GameServer
) {
    val parts = message.split(" ".toRegex()).filter { it.isNotBlank() }
    val command = parts.getOrNull(0)?.uppercase() ?: return

    when (command) {
        "PING" -> server.push(playerName, "PONG")
            .onFailure { cause -> println("Could not reply to '$playerName': ${cause.message}") }

        "SET_DEST" -> {
            val index = parts.getOrNull(1)?.toIntOrNull()
            val x = parts.getOrNull(2)?.toDoubleOrNull()
            val y = parts.getOrNull(3)?.toDoubleOrNull()
            if (index != null && x != null && y != null) {
                // index is a position in the broadcast list the client picked against - the
                // VisibleObjects among world.gameObjects, in insertion order (the same list,
                // same order, as the STATE broadcast below). Honour the move only when that
                // slot holds an Alive, the Alive has an owner, and the owner is the sender's
                // player.
                val target = world.gameObjects.filterIsInstance<VisibleObject>().getOrNull(index)
                if (target is Alive) {
                    val owner = target.owner
                    if (owner != null && owner === players[playerName]) {
                        target.destination = Point(x = x, y = y)
                    }
                }
            }
        }

        "SET_SPEED" -> {
            val index = parts.getOrNull(1)?.toIntOrNull()
            val speed = parts.getOrNull(2)?.toDoubleOrNull()
            if (index != null && speed != null && index in actors.indices) {
                actors[index].speed = ModularStat(base = speed)
            }
        }

        "STOP" -> {
            val index = parts.getOrNull(1)?.toIntOrNull()
            if (index != null && index in actors.indices) {
                actors[index].destination = Point(actors[index].location)
            }
        }

        "ATTACK" -> {
            val attackerIndex = parts.getOrNull(1)?.toIntOrNull()
            val targetIndex = parts.getOrNull(2)?.toIntOrNull()
            if (attackerIndex != null && targetIndex != null) {
                issuePlayerAttack(playerName, attackerIndex, targetIndex, world, players)
            }
        }

        else -> println("Unknown command from '$playerName': $message")
    }
}

/**
 * Orders [playerName]'s [Alive] at broadcast-list slot [attackerIndex] to attack the [Alive]
 * at slot [targetIndex], when the order is legal.
 *
 * Both indices are positions in the broadcast list - the [VisibleObject]s among
 * [World.gameObjects] in insertion order - the same list, in the same order, the client
 * picked against in the last `STATE`. The attack is issued only when every check passes:
 * both slots hold an [Alive], they are not the same actor, the attacker's [Alive.owner] is
 * the sending [Player], and the target is not one of that same player's own units. Any
 * failure is silently ignored, mirroring how [handleClientMessage] drops an unauthorised
 * `SET_DEST`.
 *
 * Runs on GameServer's listener thread(s) and calls [Alive.issueAttack], mutating combat
 * state the main loop reads concurrently - the same "fine for a demo, not hardened" caveat
 * as the rest of [handleClientMessage].
 *
 * @return `true` when an attack was issued, `false` when the order was rejected
 */
internal fun issuePlayerAttack(
    playerName: String,
    attackerIndex: Int,
    targetIndex: Int,
    world: World,
    players: Map<String, Player>
): Boolean {
    val visibles = world.gameObjects.filterIsInstance<VisibleObject>()
    val attacker = visibles.getOrNull(attackerIndex) as? Alive ?: return false
    val target = visibles.getOrNull(targetIndex) as? Alive ?: return false
    if (attacker === target) return false

    val player = players[playerName]
    if (player == null || attacker.owner !== player || target.owner === player) return false

    attacker.issueAttack(target)
    return true
}

/**
 * Handles a structured mouse event delivered by a client as an `INPUT <json>` datagram.
 * GameServer 1.2.0 decodes these into [MouseAction]s and routes them here, separately from
 * the free-text commands that reach [handleClientMessage].
 *
 *   PRESS   -> aims actor 0 at the clicked point (same effect as "SET_DEST 0 <x> <y>")
 *   MOVE    -> ignored (cursor tracking is not modelled in this demo)
 *   RELEASE -> ignored
 *
 * Coordinates arrive in the client's window pixel space (origin top-left) and are used here
 * as world coordinates unchanged, mirroring how SET_DEST treats its raw operands.
 *
 * Like [handleClientMessage], this runs on GameServer's listener thread(s) and mutates Actor
 * state that the main loop reads concurrently - fine for a demo, not hardened.
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

fun main() {
    val actors = listOf(
        // turns = false: these actors are drawn upright, so their facing angle is not
        // meaningful to a renderer even though they still track one internally.
        Alive(Point(x = 0.0, y = 0.0), Dimensions(width = 120.0, height = 120.0), 100.0).apply {
            destination = Point(x = 200.0, y = 0.0)
            texture = "minecraftzombie.png"
            turns = false
        },
        Alive(Point(x = 50.0, y = 50.0), Dimensions(width = 120.0, height = 120.0), 100.0).apply {
            destination = Point(x = 50.0, y = 300.0)
            speed = ModularStat(base = 15.0)
            texture = "minecraftzombie.png"
            turns = false
        },
        Alive(Point(x = -100.0, y = 100.0), Dimensions(width = 120.0, height = 120.0), 100.0).apply {
            destination = Point(x = 100.0, y = -100.0)
            speed = ModularStat(base = 5.0)
            texture = "minecraftzombie.png"
            turns = false
        }
    )

    // A large tiled floor for the actors to stand on. It has no behaviour of its own; it is
    // added to the world first so clients draw it behind everything else.
    val floor = VisibleObject(width = 6_000.0, height = 6_000.0, x = 0.0, y = 0.0).apply {
        texture = "whitetilefloor.jpg"
    }

    // GameTools' World: owns the game objects and a quadtree it rebuilds from their positions
    // at the start of every tick(). World.add (1.6.0) also wires an Alive's back-reference to
    // the world so a death can queue it for removal; anything created later just needs the
    // same call to be ticked, indexed, and broadcast.
    val world = World().apply {
        add(floor)
        actors.forEach { add(it) }
    }

    // GameServer exposes no connect/disconnect callback to game code, so the loop reconciles
    // this map against server.playerNames each frame: a name that appeared gets a Player with
    // a roster of Alives, a name that vanished has its roster removed from the world. It is
    // concurrent because the listener-thread message handler reads it (to resolve the sender's
    // roster for SET_DEST) while the loop thread reconciles it.
    val players = ConcurrentHashMap<String, Player>()

    // GameServer's callbacks are constructor parameters with no public setter,
    // but handleClientMessage needs a reference to the server itself (to reply
    // via push()). serverRef sidesteps the chicken-and-egg problem: the lambda
    // only reads it once a message actually arrives, by which point the
    // assignment below has long since happened.
    //
    // onPlayerMessage is passed by name because GameServer 1.2.0 added a third
    // parameter (onPlayerInput) - a trailing lambda would now bind to that one.
    var serverRef: GameServer? = null
    val server = GameServer(
        maxConnections = 4,
        onPlayerMessage = { playerName, message ->
            serverRef?.let { handleClientMessage(playerName, message, actors, world, players, it) }
        },
        onPlayerInput = { playerName, input ->
            handleClientInput(playerName, input, actors)
        }
    )
    serverRef = server

    val tickIntervalNanos = 1_000_000_000L / 60L // 60 times per second
    var nextTick = System.nanoTime()

    try {
        while (true) {
            val connected = server.playerNames
            (connected - players.keys).forEach { name -> players[name] = connectPlayer(name, world) }
            (players.keys - connected).forEach { name -> disconnectPlayer(players.remove(name)!!, world) }

            world.tick() // rebuilds the quadtree, then advances every object one step

            // Sends every VisibleObject in the world as a single "STATE <json>" datagram to
            // every player. This is the exact list (same order) that SET_DEST indices resolve
            // against.
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