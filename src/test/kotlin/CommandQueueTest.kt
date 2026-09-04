import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [drainPendingCommands], the mechanism behind issue #1's fix: commands queued from a
 * listener thread must run, in order, when drained on the loop thread.
 */
class CommandQueueTest {

    @Test
    fun `queued commands run in FIFO order and are removed from the queue`() {
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val ran = mutableListOf<Int>()
        queue.add { ran += 1 }
        queue.add { ran += 2 }
        queue.add { ran += 3 }

        drainPendingCommands(queue)

        assertEquals(listOf(1, 2, 3), ran)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `draining an empty queue runs nothing`() {
        val queue = ConcurrentLinkedQueue<() -> Unit>()

        drainPendingCommands(queue) // must not throw

        assertTrue(queue.isEmpty())
    }
}
