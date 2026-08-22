import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

// TODO: point this at the real package for Actor.
// e.g. import com.spartanlabs.actors.Actor

@Serializable
data class ActorSnapshot(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val destX: Double,
    val destY: Double,
    val speed: Double,
    val baseSpeed: Double,
    val speedModifier: Double,
    val texture: String
) {
    companion object {
        fun from(id: String, actor: Actor): ActorSnapshot {
            val loc = actor.area.location
            val dim = actor.area.dimensions
            val dest = actor.destination
            return ActorSnapshot(
                id = id,
                x = loc.x,
                y = loc.y,
                width = dim.width,
                height = dim.height,
                destX = dest.x,
                destY = dest.y,
                speed = actor.speed,
                baseSpeed = actor.baseSpeed,
                speedModifier = actor.speedModifier,
                texture = actor.texture
            )
        }
    }
}

/**
 * Pushes Actor state to [targetAddress]:[sendPort] as JSON over UDP, and
 * separately listens on [listenPort] for incoming datagrams, handing each
 * raw message off to a caller-supplied handler. Server has no knowledge of
 * Actor beyond serializing snapshots for [pushActors] - what a received
 * message means is entirely up to the caller of [startListening].
 *
 * Outbound (pushes + replies) and inbound (client commands) use two
 * separate sockets/ports so the two directions don't interfere.
 */
class Server(
    private val targetAddress: InetAddress = resolveLocalAddress(),
    private val sendPort: Int,
    private val listenPort: Int
) : AutoCloseable {

    private val sendSocket = DatagramSocket()
    private val listenSocket = DatagramSocket(listenPort)

    @Volatile
    private var listening = false
    private var listenerThread: Thread? = null

    /**
     * Serializes [actors] to a JSON array and sends it as a single UDP
     * datagram to [targetAddress]:[sendPort].
     */
    fun pushActors(actors: List<Actor>) {
        val snapshots = actors.mapIndexed { index, actor ->
            ActorSnapshot.from("actor-$index", actor)
        }
        val json = Json.encodeToString(snapshots)
        val bytes = json.toByteArray(Charsets.UTF_8)
        sendSocket.send(DatagramPacket(bytes, bytes.size, targetAddress, sendPort))
    }

    /**
     * Starts a background thread that blocks on [listenSocket]'s receive,
     * decoding each incoming datagram as a UTF-8 string and passing it to
     * [onMessage] along with the sender's address.
     */
    fun startListening(onMessage: (message: String, senderAddress: InetAddress) -> Unit) {
        listening = true
        listenerThread = Thread {
            val buffer = ByteArray(1024)
            while (listening) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    listenSocket.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    onMessage(message, packet.address)
                } catch (e: SocketException) {
                    break // socket was closed - stop listening
                } catch (e: Exception) {
                    println("Failed to handle incoming datagram: ${e.message}")
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stopListening() {
        listening = false
    }

    /**
     * Sends a reply datagram to a specific client, e.g. from within
     * [onMessage]. Replies go out on [sendPort], same as [pushActors].
     */
    fun respond(message: String, address: InetAddress) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        sendSocket.send(DatagramPacket(bytes, bytes.size, address, sendPort))
    }

    override fun close() {
        stopListening()
        listenSocket.close()
        sendSocket.close()
        listenerThread?.join(1000)
    }

    companion object {
        fun resolveLocalAddress(): InetAddress =
            try {
                DatagramSocket().use { probe ->
                    probe.connect(InetAddress.getByName("8.8.8.8"), 80)
                    probe.localAddress
                }
            } catch (e: Exception) {
                InetAddress.getLoopbackAddress()
            }
    }
}