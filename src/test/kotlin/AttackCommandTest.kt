import com.spartanlabs.gaming.gameobjects.Alive
import com.spartanlabs.gaming.gameobjects.Player
import com.spartanlabs.gaming.gameobjects.VisibleObject
import com.spartanlabs.gaming.gameobjects.World
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [issuePlayerAttack], the authorization + dispatch behind the `ATTACK` command in
 * Main.kt's [handleClientMessage]. Mirrors the ownership checks [PlayerRosterTest] relies on
 * (GameTools 1.5.2+ keeps `Player.ownedAlives` / `Alive.owner` in step), applied to combat.
 */
class AttackCommandTest {

    private class Fixture {
        val world = World()
        val alice = Player("alice")
        val bob = Player("bob")

        // Close enough to be inside the default 750-unit attackRange straight away.
        val aliceUnit = alive(0.0, 0.0).also { alice.own(it); world.add(it) }   // broadcast index 0
        val bobUnit = alive(50.0, 0.0).also { bob.own(it); world.add(it) }      // broadcast index 1

        val players = mapOf("alice" to alice, "bob" to bob)

        private fun alive(x: Double, y: Double) =
            Alive(Point(x, y), Dimensions(width = 10.0, height = 10.0), maxHealth = 100.0)
    }

    @Test
    fun `an owned attacker may attack an enemy unit and actually damages it`() {
        val f = Fixture()

        val issued = issuePlayerAttack("alice", attackerIndex = 0, targetIndex = 1, f.world, f.players)

        assertTrue(issued)
        repeat(200) { f.world.tick() } // attackTime 1.7 at 0.01/tick -> first hit lands well before 200
        assertTrue(f.bobUnit.health.current < 100.0, "the target should have lost health")
    }

    @Test
    fun `a player cannot drive an Alive it does not own`() {
        val f = Fixture()

        // "bob" naming "alice"'s unit (index 0) as the attacker.
        val issued = issuePlayerAttack("bob", attackerIndex = 0, targetIndex = 1, f.world, f.players)

        assertFalse(issued)
        repeat(200) { f.world.tick() }
        assertTrue(f.bobUnit.health.current == f.bobUnit.health.max.value, "no attack should have run")
    }

    @Test
    fun `attacking your own unit is rejected`() {
        val f = Fixture()
        val ownUnit = Alive(Point(20.0, 0.0), Dimensions(10.0, 10.0), 100.0)
            .also { f.alice.own(it); f.world.add(it) } // broadcast index 2

        assertFalse(issuePlayerAttack("alice", attackerIndex = 0, targetIndex = 2, f.world, f.players))
    }

    @Test
    fun `a target slot that is not an Alive is rejected`() {
        val f = Fixture()
        val scenery = VisibleObject(width = 100.0, height = 100.0, x = 200.0, y = 200.0)
            .also { f.world.add(it) } // broadcast index 2

        assertFalse(issuePlayerAttack("alice", attackerIndex = 0, targetIndex = 2, f.world, f.players))
    }

    @Test
    fun `an out-of-range index is rejected`() {
        val f = Fixture()

        assertFalse(issuePlayerAttack("alice", attackerIndex = 0, targetIndex = 99, f.world, f.players))
        assertFalse(issuePlayerAttack("alice", attackerIndex = -1, targetIndex = 1, f.world, f.players))
    }

    @Test
    fun `an actor cannot attack itself`() {
        val f = Fixture()

        assertFalse(issuePlayerAttack("alice", attackerIndex = 0, targetIndex = 0, f.world, f.players))
    }

    @Test
    fun `an unknown player name is rejected`() {
        val f = Fixture()

        assertFalse(issuePlayerAttack("mallory", attackerIndex = 0, targetIndex = 1, f.world, f.players))
    }
}
