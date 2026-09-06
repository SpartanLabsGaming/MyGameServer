import com.spartanlabs.gaming.gameobjects.Alive
import com.spartanlabs.gaming.gameobjects.Player
import com.spartanlabs.gaming.gameobjects.VisibleObject
import com.spartanlabs.gaming.gameobjects.World
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers [resolveOwnedAlive], the authorization behind the `SET_DEST` / `SET_SPEED` (Alive
 * case) commands in Main.kt's [handleClientMessage]: an entity id is honoured only when it
 * still names an [Alive] the sending [Player] owns. Same ownership rules as
 * [AttackCommandTest], on the movement path.
 */
class SetDestCommandTest {

    private val world = World()
    private val alice = Player("alice")
    private val bob = Player("bob")
    private val players = mapOf("alice" to alice, "bob" to bob)

    private fun alive(x: Double, y: Double) =
        Alive(Point(x, y), Dimensions(10.0, 10.0), 100.0)

    private val aliceUnit = alive(0.0, 0.0).also { alice.own(it); world.add(it) }

    @Test
    fun `an owner's own unit resolves`() {
        assertSame(aliceUnit, resolveOwnedAlive(aliceUnit.entityId.raw, "alice", world, players))
    }

    @Test
    fun `another player's unit does not resolve`() {
        assertNull(resolveOwnedAlive(aliceUnit.entityId.raw, "bob", world, players))
    }

    @Test
    fun `an unowned unit does not resolve`() {
        val wild = alive(30.0, 0.0).also { world.add(it) }
        assertNull(resolveOwnedAlive(wild.entityId.raw, "alice", world, players))
    }

    @Test
    fun `a non-Alive id does not resolve`() {
        val scenery = VisibleObject(width = 10.0, height = 10.0, x = 5.0, y = 5.0).also { world.add(it) }
        assertNull(resolveOwnedAlive(scenery.entityId.raw, "alice", world, players))
    }

    @Test
    fun `an unknown id does not resolve`() {
        assertNull(resolveOwnedAlive(999_999L, "alice", world, players))
        assertNull(resolveOwnedAlive(0L, "alice", world, players))
    }

    @Test
    fun `an unknown player name does not resolve`() {
        assertNull(resolveOwnedAlive(aliceUnit.entityId.raw, "mallory", world, players))
    }

    @Test
    fun `a since-removed unit does not resolve`() {
        val doomed = alive(40.0, 0.0).also { alice.own(it); world.add(it) }
        val doomedId = doomed.entityId.raw

        world.gameObjects.remove(doomed)
        world.tick()

        assertNull(resolveOwnedAlive(doomedId, "alice", world, players))
    }
}
