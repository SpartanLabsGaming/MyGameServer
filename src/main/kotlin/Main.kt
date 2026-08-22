import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import java.net.InetAddress

// TODO: same as Server.kt - point this at the real package for
// Actor once confirmed (e.g. com.spartanlabs.actors.Actor).

/**
 * Minimal concrete Actor. Actor -> VisibleObject is abstract (VisibleObject
 * declares `abstract fun draw()`), so a concrete subclass is required just
 * to instantiate one. This process is headless (no rendering), so draw()
 * is a no-op.
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
 *   PING                          -> replies "PONG"
 *   SET_DEST <index> <x> <y>      -> sets actors[index].destination
 *   SET_SPEED <index> <speed>     -> sets actors[index].baseSpeed
 *   STOP <index>                  -> sets destination to current location
 *
 * Note: this mutates Actor state from the Server's listener thread while
 * the main loop ticks/reads that same state - fine for a simple demo, but
 * not hardened against concurrent access.
 */
private fun handleClientMessage(
    message: String,
    actors: List<Actor>,
    server: Server,
    senderAddress: InetAddress
) {
    val parts = message.split(" ".toRegex()).filter { it.isNotBlank() }
    val command = parts.getOrNull(0)?.uppercase() ?: return

    when (command) {
        "PING" -> server.respond("PONG", senderAddress)

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
                actors[index].destination = Point(actors[index].area.location)
            }
        }

        else -> println("Unknown command: $message")
    }
}

fun main() {
    val server = Server(sendPort = 9999, listenPort = 9998)

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

    val tickIntervalNanos = 1_000_000_000L / 60L // 60 times per second
    var nextTick = System.nanoTime()

    server.startListening { message, senderAddress ->
        handleClientMessage(message, actors, server, senderAddress)
    }

    server.use {
        while (true) {
            actors.forEach { it.tick() } // advances each actor toward its destination
            server.pushActors(actors)

            nextTick += tickIntervalNanos
            val sleepNanos = nextTick - System.nanoTime()
            if (sleepNanos > 0) {
                Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            } else {
                // Fell behind (e.g. a slow tick) - resync instead of spinning to catch up.
                nextTick = System.nanoTime()
            }
        }
    }
}