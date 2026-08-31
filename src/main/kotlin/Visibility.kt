import com.spartanlabs.gaming.gameobjects.VisibleObject
import com.spartanlabs.gaming.gameobjects.World

/**
 * The [VisibleObject]s a [World] owns - the objects that can be drawn and broadcast, pulled
 * out of its mixed [World.gameObjects] list.
 */
val World.visibleObjects: List<VisibleObject>
    get() = gameObjects.filterIsInstance<VisibleObject>()

/**
 * The objects that actually go out in a `STATE` broadcast: [visibleObjects] with the
 * individually hidden ones ([VisibleObject.visible] `false`, e.g. a spent projectile)
 * removed, in the order the client receives them.
 *
 * This is the index space clients address objects in - a `SET_DEST <index>` names a position
 * in *this* list, not in [World.gameObjects], because this is the list the client picked
 * against. Resolve incoming positional commands here so an omitted object does not shift them.
 */
val World.broadcastObjects: List<VisibleObject>
    get() = visibleObjects.filter { it.visible }
