import com.spartanlabs.gaming.event.GameEvent
import com.spartanlabs.gaming.gameobjects.Alive
import com.spartanlabs.gaming.gameobjects.EntityId
import com.spartanlabs.gaming.gameobjects.Player
import com.spartanlabs.gaming.gameobjects.VisibleObject
import com.spartanlabs.gaming.gameobjects.World
import com.spartanlabs.gaming.networking.command.Attack
import com.spartanlabs.gaming.networking.command.ClientCommand
import com.spartanlabs.gaming.networking.command.Follow
import com.spartanlabs.gaming.networking.command.MoveDir
import com.spartanlabs.gaming.networking.command.MoveTo
import com.spartanlabs.gaming.networking.command.Stop
import com.spartanlabs.gaming.networking.command.StopAttack
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [handleCommand] - the authorize-then-[com.spartanlabs.gaming.networking.command.applyTo]
 * dispatch behind a decoded `COMMAND <json>` ([ClientCommand]) in Main.kt. Drives it directly,
 * with no network: the codec/`GameServer` decode seam is GameTools' and is exercised upstream.
 *
 * The ownership predicates it leans on ([resolveOwnedAlive], [authorizeAttack]) have their own
 * exhaustive matrices in [MoveCommandTest] and [AttackCommandTest]; this class checks that
 * [handleCommand] wires each command to the right predicate and the right mechanism, honours
 * the three supported commands, and drops the rest without throwing.
 */
class ClientCommandDispatchTest {

    private class Fixture {
        val world = World()
        val alice = Player("alice")
        val bob = Player("bob")

        val aliceUnit = alive(0.0, 0.0).also { alice.own(it); world.add(it) }
        val aliceUnit2 = alive(10.0, 0.0).also { alice.own(it); world.add(it) }
        val bobUnit = alive(40.0, 0.0).also { bob.own(it); world.add(it) }
        val zombie = alive(-40.0, 0.0).also { world.add(it) } // unowned
        val scenery = VisibleObject(width = 20.0, height = 20.0, x = 100.0, y = 100.0).also { world.add(it) }

        val players = mapOf("alice" to alice, "bob" to bob)

        val cancelled = mutableListOf<GameEvent.AttackCancelled>()

        init {
            world.events.subscribe { if (it is GameEvent.AttackCancelled) cancelled += it }
        }

        fun alive(x: Double, y: Double) =
            Alive(Point(x, y), Dimensions(width = 10.0, height = 10.0), maxHealth = 100.0)

        fun send(command: ClientCommand, from: String = "alice") =
            handleCommand(from, command, world, players)
    }

    @Test
    fun `MoveTo on an owned unit sets its destination`() {
        val f = Fixture()

        f.send(MoveTo(f.aliceUnit.entityId, 250.0, -75.0))

        assertEquals(250.0, f.aliceUnit.destination.x)
        assertEquals(-75.0, f.aliceUnit.destination.y)
    }

    @Test
    fun `MoveTo breaks off a pending attack`() {
        val f = Fixture()
        f.send(Attack(f.aliceUnit.entityId, f.bobUnit.entityId))
        repeat(30) { f.world.tick() }

        f.send(MoveTo(f.aliceUnit.entityId, -500.0, 0.0))
        repeat(20) { f.world.tick() }

        assertTrue(f.cancelled.any { it.attacker === f.aliceUnit }, "the attack should have been cancelled")
        // With the attack cancelled the move order stands; otherwise considerAttack would keep
        // overwriting the destination with the target's position each tick.
        assertEquals(-500.0, f.aliceUnit.destination.x)
    }

    @Test
    fun `MoveTo naming another player's unit is ignored`() {
        val f = Fixture()
        val before = f.bobUnit.destination.x

        f.send(MoveTo(f.bobUnit.entityId, 999.0, 999.0))

        assertEquals(before, f.bobUnit.destination.x)
    }

    @Test
    fun `MoveTo naming a non-Alive is a no-op`() {
        val f = Fixture()
        f.send(MoveTo(f.scenery.entityId, 1.0, 2.0)) // must not throw
    }

    @Test
    fun `MoveTo with an unknown or unassigned id is a no-op`() {
        val f = Fixture()
        f.send(MoveTo(EntityId(999_999L), 1.0, 2.0))
        f.send(MoveTo(EntityId.UNASSIGNED, 1.0, 2.0)) // must not throw
    }

    @Test
    fun `Stop pins an owned unit's destination to its current location`() {
        val f = Fixture()
        f.aliceUnit.destination = Point(x = 400.0, y = 0.0)
        repeat(5) { f.world.tick() } // let it start moving

        f.send(Stop(f.aliceUnit.entityId))

        assertEquals(f.aliceUnit.location.x, f.aliceUnit.destination.x)
        assertEquals(f.aliceUnit.location.y, f.aliceUnit.destination.y)
    }

    @Test
    fun `Stop does not touch an unowned zombie`() {
        val f = Fixture()
        f.zombie.destination = Point(x = -400.0, y = 0.0)

        f.send(Stop(f.zombie.entityId))

        assertEquals(-400.0, f.zombie.destination.x, "an unowned actor must not be stoppable")
    }

    @Test
    fun `Stop naming another player's unit is ignored`() {
        val f = Fixture()
        f.bobUnit.destination = Point(x = 400.0, y = 0.0)

        f.send(Stop(f.bobUnit.entityId))

        assertEquals(400.0, f.bobUnit.destination.x)
    }

    @Test
    fun `Attack from an authorized attacker damages the enemy target`() {
        val f = Fixture()

        f.send(Attack(f.aliceUnit.entityId, f.bobUnit.entityId))
        repeat(200) { f.world.tick() }

        assertTrue(f.bobUnit.health.current < 100.0)
    }

    @Test
    fun `Attack with an attacker the sender does not own does nothing`() {
        val f = Fixture()

        f.send(Attack(f.bobUnit.entityId, f.aliceUnit.entityId)) // alice naming bob's unit
        repeat(200) { f.world.tick() }

        assertEquals(100.0, f.aliceUnit.health.current)
    }

    @Test
    fun `Attack on the sender's own unit does nothing`() {
        val f = Fixture()

        f.send(Attack(f.aliceUnit.entityId, f.aliceUnit2.entityId))
        repeat(200) { f.world.tick() }

        assertEquals(100.0, f.aliceUnit2.health.current)
    }

    @Test
    fun `unsupported standard commands are ignored without throwing`() {
        val f = Fixture()
        val destBefore = f.aliceUnit.destination.x

        f.send(MoveDir(f.aliceUnit.entityId, 90))
        f.send(Follow(f.aliceUnit.entityId, f.bobUnit.entityId))
        f.send(StopAttack(f.aliceUnit.entityId))

        assertEquals(destBefore, f.aliceUnit.destination.x, "an ignored command must not mutate state")
    }

    @Test
    fun `an unknown ClientCommand implementation is ignored without throwing`() {
        val f = Fixture()

        f.send(object : ClientCommand {}) // must not throw
    }
}
