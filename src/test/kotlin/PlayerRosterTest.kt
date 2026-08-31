import com.spartanlabs.gaming.gameobjects.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the connect/disconnect roster wiring in Main.kt against GameTools' [com.spartanlabs.gaming.gameobjects.Player]
 * / [com.spartanlabs.gaming.gameobjects.Alive] ownership, which since 1.5.2 keeps
 * `Player.ownedAlives` and `Alive.owner` in step in both directions.
 */
class PlayerRosterTest {

    @Test
    fun `connectPlayer builds a full roster owned both ways and adds it to the world`() {
        val world = World()

        val player = connectPlayer("alice", world)

        assertEquals(ALIVES_PER_PLAYER, player.ownedAlives.size)
        assertEquals(ALIVES_PER_PLAYER, world.gameObjects.size)
        player.ownedAlives.forEach { alive ->
            assertSame(player, alive.owner, "each owned Alive should back-reference its player")
            assertTrue(alive in world.gameObjects, "each owned Alive should be in the world")
        }
    }

    @Test
    fun `disconnectPlayer clears the roster and removes the actors from the world without CME`() {
        val world = World()
        val player = connectPlayer("bob", world)
        val roster = player.ownedAlives.toList()

        disconnectPlayer(player, world)

        assertEquals(0, player.ownedAlives.size)
        assertTrue(world.gameObjects.isEmpty())
        roster.forEach { assertNull(it.owner, "owner should be cleared on disconnect") }
    }

    @Test
    fun `a second player's roster is independent of the first`() {
        val world = World()
        val alice = connectPlayer("alice", world)
        val bob = connectPlayer("bob", world)

        assertEquals(2 * ALIVES_PER_PLAYER, world.gameObjects.size)

        disconnectPlayer(alice, world)

        assertEquals(0, alice.ownedAlives.size)
        assertEquals(ALIVES_PER_PLAYER, bob.ownedAlives.size)
        assertEquals(ALIVES_PER_PLAYER, world.gameObjects.size)
    }
}
