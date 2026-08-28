import com.spartanlabs.gaming.gameobjects.Actor
import com.spartanlabs.gaming.networking.GameServer
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point

/**
 * Minimal concrete Actor. Actor -> VisibleObject is abstract (VisibleObject
 * declares `abstract fun draw()`), and GameObject (the root) declares
 * `abstract fun onUpdate()`, so a concrete subclass is required just to
 * instantiate one. This process is headless (no rendering), so draw() is a
 * no-op, and no extra per-tick logic is needed beyond what Actor.tick()
 * already does (onUpdate() -> draw() -> move()).
 */
class SimpleActor(location: Point, dimensions: Dimensions) : Actor(location, dimensions) {
    override fun draw() {
        // No rendering here - this process only tracks and broadcasts state.
    }

    override fun onUpdate() {
        // No per-tick game logic needed here - movement is already handled
        // by Actor.tick()/move(); this process just observes/broadcasts it.
    }
}

/**
 * Parses a raw client message (whitespace-separated, case-insensitive
 * command) and dispatches on the first token:
 *   PING                          -> replies "PONG" to just that player
 *   SET_DEST <index> <x> <y>      -> sets actors[index].destination
 *   SET_SPEED <index> <speed>     -> sets actors[index].baseSpeed
 *   STOP <index>                  -> sets destination to current location
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
            if (index != null && x != null && y != null && index in actors.indices) {
                actors[index].destination = Point(x = x, y = y)
            }
        }

        "SET_SPEED" -> {
            val index = parts.getOrNull(1)?.toIntOrNull()
            val speed = parts.getOrNull(2)?.toDoubleOrNull()
            if (index != null && speed != null && index in actors.indices) {
                actors[index].baseSpeed = speed
            }
        }

        "STOP" -> {
            val index = parts.getOrNull(1)?.toIntOrNull()
            if (index != null && index in actors.indices) {
                actors[index].destination = Point(actors[index].location)
            }
        }

        else -> println("Unknown command from '$playerName': $message")
    }
}

fun main() {
    val actors = listOf(
        SimpleActor(Point(x = 0.0, y = 0.0), Dimensions(width = 25.0, height = 25.0)).apply {
            destination = Point(x = 200.0, y = 0.0)
            texture = "res/natures prophet.jpg"
        },
        SimpleActor(Point(x = 50.0, y = 50.0), Dimensions(width = 25.0, height = 25.0)).apply {
            destination = Point(x = 50.0, y = 300.0)
            baseSpeed = 15.0
            texture = "res/natures prophet.jpg"
        },
        SimpleActor(Point(x = -100.0, y = 100.0), Dimensions(width = 25.0, height = 25.0)).apply {
            destination = Point(x = 100.0, y = -100.0)
            baseSpeed = 5.0
            texture = "res/natures prophet.jpg"
        }
    )

    // GameServer's onPlayerMessage callback is a constructor parameter with
    // no public setter, but the handler needs a reference to the server
    // itself (to reply via push()). serverRef sidesteps the chicken-and-egg
    // problem: the lambda only reads it once a message actually arrives,
    // by which point the assignment below has long since happened.
    var serverRef: GameServer? = null
    val server = GameServer(maxConnections = 4) { playerName, message ->
        serverRef?.let { handleClientMessage(playerName, message, actors, it) }
    }
    serverRef = server

    val tickIntervalNanos = 1_000_000_000L / 60L // 60 times per second
    var nextTick = System.nanoTime()

    try {
        while (true) {
            actors.forEach { it.tick() } // advances each actor toward its destination

            // Sends the whole actor list as a single "STATE <json>" datagram
            // to every connected player.
            server.broadcast(actors)
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