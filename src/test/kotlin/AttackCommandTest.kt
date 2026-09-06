import com.spartanlabs.gaming.gameobjects.Alive
import com.spartanlabs.gaming.gameobjects.EntityId
import com.spartanlabs.gaming.gameobjects.Player
import com.spartanlabs.gaming.gameobjects.VisibleObject
import com.spartanlabs.gaming.gameobjects.World
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [issuePlayerAttack], the authorization + dispatch behind the `ATTACK` command in
 * Main.kt's [handleClientMessage]. Mirrors the ownership checks [PlayerRosterTest] relies on
 * (GameTools 1.5.2+ keeps `Player.ownedAlives` / `Alive.owner` in step), applied to combat.
 *
 * Operands are `Alive` entity ids (`entityId.raw`) as of GameTools 3.1.0 - `World.add`
 * numbers each object, so a unit's id is fixed the moment it enters the fixture's world and
 * stays valid regardless of the broadcast list's order (see the id-stability tests at the
 * end).
 */
class AttackCommandTest {

    private class Fixture {
        val world = World()
        val alice = Player("alice")
        val bob = Player("bob")

        // Close enough to be inside the default 750-unit attackRange straight away.
        val aliceUnit = alive(0.0, 0.0).also { alice.own(it); world.add(it) }
        val bobUnit = alive(50.0, 0.0).also { bob.own(it); world.add(it) }

        val players = mapOf("alice" to alice, "bob" to bob)

        fun alive(x: Double, y: Double) =
            Alive(Point(x, y), Dimensions(width = 10.0, height = 10.0), maxHealth = 100.0)
    }

    @Test
    fun `an owned attacker may attack an enemy unit and actually damages it`() {
        val f = Fixture()

        val issued = issuePlayerAttack(
            "alice", f.aliceUnit.entityId.raw, f.bobUnit.entityId.raw, f.world, f.players
        )

        assertTrue(issued)
        repeat(200) { f.world.tick() } // attackTime 1.7 at 0.01/tick -> first hit lands well before 200
        assertTrue(f.bobUnit.health.current < 100.0, "the target should have lost health")
    }

    @Test
    fun `a player cannot drive an Alive it does not own`() {
        val f = Fixture()

        // "bob" naming "alice"'s unit as the attacker.
        val issued = issuePlayerAttack(
            "bob", f.aliceUnit.entityId.raw, f.bobUnit.entityId.raw, f.world, f.players
        )

        assertFalse(issued)
        repeat(200) { f.world.tick() }
        assertTrue(f.bobUnit.health.current == f.bobUnit.health.max.value, "no attack should have run")
    }

    @Test
    fun `attacking your own unit is rejected`() {
        val f = Fixture()
        val ownUnit = f.alive(20.0, 0.0).also { f.alice.own(it); f.world.add(it) }

        assertFalse(
            issuePlayerAttack("alice", f.aliceUnit.entityId.raw, ownUnit.entityId.raw, f.world, f.players)
        )
    }

    @Test
    fun `a target id that is not an Alive is rejected`() {
        val f = Fixture()
        val scenery = VisibleObject(width = 100.0, height = 100.0, x = 200.0, y = 200.0)
            .also { f.world.add(it) }

        assertFalse(
            issuePlayerAttack("alice", f.aliceUnit.entityId.raw, scenery.entityId.raw, f.world, f.players)
        )
    }

    @Test
    fun `an unknown id is rejected`() {
        val f = Fixture()

        assertFalse(issuePlayerAttack("alice", f.aliceUnit.entityId.raw, 999_999L, f.world, f.players))
        // 0 is EntityId.UNASSIGNED / DrawableSnapshot.UNIDENTIFIED - a World never hands it out.
        assertFalse(issuePlayerAttack("alice", 0L, f.bobUnit.entityId.raw, f.world, f.players))
    }

    @Test
    fun `an actor cannot attack itself`() {
        val f = Fixture()

        assertFalse(
            issuePlayerAttack("alice", f.aliceUnit.entityId.raw, f.aliceUnit.entityId.raw, f.world, f.players)
        )
    }

    @Test
    fun `an unknown player name is rejected`() {
        val f = Fixture()

        assertFalse(
            issuePlayerAttack("mallory", f.aliceUnit.entityId.raw, f.bobUnit.entityId.raw, f.world, f.players)
        )
    }

    @Test
    fun `a command addressed to a since-removed unit is rejected`() {
        val f = Fixture()
        val doomed = f.alive(60.0, 0.0).also { f.bob.own(it); f.world.add(it) }
        val doomedId = doomed.entityId.raw

        f.world.gameObjects.remove(doomed)
        f.world.tick() // World rebuilds byId from gameObjects each tick

        assertFalse(issuePlayerAttack("alice", f.aliceUnit.entityId.raw, doomedId, f.world, f.players))
    }

    @Test
    fun `ids stay bound to their object when an earlier object is removed`() {
        val f = Fixture()
        // A third unit after the two fixture units; removing the first one shifts every
        // later broadcast-list position but must not change what an id resolves to.
        val third = f.alive(70.0, 0.0).also { f.bob.own(it); f.world.add(it) }
        val bobId = f.bobUnit.entityId.raw
        val thirdId = third.entityId.raw

        f.world.gameObjects.remove(f.aliceUnit)
        f.world.tick()

        assertSame(f.bobUnit, f.world.byId(EntityId(bobId)))
        assertSame(third, f.world.byId(EntityId(thirdId)))
    }
}
