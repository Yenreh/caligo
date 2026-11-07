package com.yenreh.caligo.ui.lab

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yenreh.caligo.R
import com.yenreh.caligo.ui.theme.LabTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * A list whose items move by holding one and dragging it, as Flutter's
 * ReorderableListView does on a phone: the held item follows the finger
 * framed in accent, the others make room, the list scrolls when it
 * reaches an edge, and letting go reports the move. Screen readers get
 * the same moves as actions on each item: to the start, up, down, to the
 * end.
 *
 * [onReorder] takes Flutter's indexes: the new index counts the item
 * itself when it moves down. Until [items] changes after a drop, the list
 * keeps showing the order the drag left. Without [dragEnabled], as
 * Flutter's list without its drag handles, nothing is held, while screen
 * readers keep their moves.
 */
@Composable
fun <T> LabReorderableList(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (oldIndex: Int, newIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
    dragEnabled: Boolean = true,
    itemContent: @Composable (item: T) -> Unit,
) {
    val p = LabTheme.palette
    val reorder by rememberUpdatedState(onReorder)
    val drag = remember(listState) { ReorderDrag(listState) }
    val scope = rememberCoroutineScope()
    val settle = remember { Animatable(0f) }
    var settlingKey by remember { mutableStateOf<Any?>(null) }

    // The order a drag has left, by key, until the list follows it
    var order by remember { mutableStateOf<List<Any>?>(null) }
    LaunchedEffect(items) { if (drag.heldKey == null) order = null }
    val byKey = items.associateBy(key)
    val shown = order?.mapNotNull { byKey[it] } ?: items

    drag.onMove = { from, to ->
        val keys = (order ?: items.map(key)).toMutableList()
        if (from in keys.indices && to in keys.indices) {
            keys.add(to, keys.removeAt(from))
            order = keys
        }
    }
    drag.onDrop = { held, offset ->
        val from = items.indexOfFirst { key(it) == held }
        val to = order?.indexOf(held) ?: from
        if (from < 0 || from == to) order = null else reorder(from, if (to > from) to + 1 else to)
        // Glides from where it was let go into its slot
        settlingKey = held
        // At once, so no frame shows it in its slot before the glide starts
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settle.snapTo(offset)
            settle.animateTo(0f)
            if (settlingKey == held) settlingKey = null
        }
    }

    // Scrolls while the held item sits past an edge, finger still or not
    LaunchedEffect(drag, drag.heldKey) {
        while (drag.heldKey != null) {
            val past = drag.pastEdge()
            if (past != 0f) {
                listState.scrollBy(past.coerceIn(-MAX_SCROLL_PER_FRAME, MAX_SCROLL_PER_FRAME))
                drag.swapIfOver()
            }
            withFrameNanos {}
        }
    }

    val toStart = stringResource(R.string.reorder_item_to_start)
    val up = stringResource(R.string.reorder_item_up)
    val down = stringResource(R.string.reorder_item_down)
    val toEnd = stringResource(R.string.reorder_item_to_end)

    LazyColumn(
        modifier = if (dragEnabled) {
            modifier.pointerInput(drag) {
                detectHoldAndDrag(onStart = drag::start, onDrag = drag::move, onEnd = drag::end)
            }
        } else {
            modifier
        },
        state = listState,
        contentPadding = contentPadding,
    ) {
        itemsIndexed(shown, key = { _, item -> key(item) }) { index, item ->
            val itemKey = key(item)
            val held = itemKey == drag.heldKey
            val settling = itemKey == settlingKey
            val count = shown.size
            val moves = buildList {
                if (index > 0) {
                    add(CustomAccessibilityAction(toStart) { reorder(index, 0); true })
                    add(CustomAccessibilityAction(up) { reorder(index, index - 1); true })
                }
                if (index < count - 1) {
                    // Past the next one: Flutter's index counts the item itself
                    add(CustomAccessibilityAction(down) { reorder(index, index + 2); true })
                    add(CustomAccessibilityAction(toEnd) { reorder(index, count); true })
                }
            }
            Box(
                Modifier
                    .then(
                        when {
                            held -> Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = drag.heldOffset }
                                .border(2.dp, p.accent)
                            settling -> Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = settle.value }
                            else -> Modifier.animateItem()
                        },
                    )
                    .semantics { customActions = moves },
            ) {
                itemContent(item)
            }
        }
    }
}

/**
 * A long press, then a drag, seen before the list and its items see the
 * touch: until the press is long enough the touch is theirs, a tap or a
 * scroll; once it is, every move is this drag's and they get none. On the
 * Main pass, as Compose's own detector works, the list would scroll first.
 */
private suspend fun PointerInputScope.detectHoldAndDrag(
    onStart: (y: Float) -> Boolean,
    onDrag: (dy: Float) -> Unit,
    onEnd: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    // Null once the wait runs out still pressed and still: a long press
    val shortOrMoved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ?: break
            if (!change.pressed || change.isConsumed) break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
        }
        true
    }
    if (shortOrMoved != null) return@awaitEachGesture

    // Nothing to pick up in the padding
    if (!onStart(down.position.y)) return@awaitEachGesture
    try {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ?: break
            val dy = change.positionChange().y
            change.consume()
            if (!change.pressed) break
            if (dy != 0f) onDrag(dy)
        }
    } finally {
        // Let go, or the touch taken away: either way it drops here
        onEnd()
    }
}

/** Where the held item is, in the list's own offsets, and where it goes */
@Stable
private class ReorderDrag(private val list: LazyListState) {
    /** The key of the item held, null while none is */
    var heldKey: Any? by mutableStateOf(null)
        private set

    /** The finger's travel since the item was picked up */
    private var travel by mutableFloatStateOf(0f)

    /** The held item's offset as it was picked up */
    private var startOffset = 0

    var onMove: (from: Int, to: Int) -> Unit = { _, _ -> }

    /** The key let go, and how far from its slot it was drawn */
    var onDrop: (key: Any, offset: Float) -> Unit = { _, _ -> }

    private val held: LazyListItemInfo?
        get() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == heldKey }

    /** How far from its slot the held item is drawn, to stay under the finger */
    val heldOffset: Float
        get() = held?.let { startOffset + travel - it.offset } ?: 0f

    /** Picks up the item at [y], measured from the list's top edge; false if none is there */
    fun start(y: Float): Boolean {
        // Item offsets count from the end of the top padding
        val at = y + list.layoutInfo.viewportStartOffset
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { at.toInt() in it.offset..(it.offset + it.size) }
            ?: return false
        heldKey = item.key
        startOffset = item.offset
        travel = 0f
        return true
    }

    fun move(dy: Float) {
        if (heldKey == null) return
        travel += dy
        swapIfOver()
    }

    /** Makes room once the held item's middle is over another one */
    fun swapIfOver() {
        val item = held ?: return
        val middle = startOffset + travel + item.size / 2f
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index != item.index && middle.toInt() in it.offset..(it.offset + it.size)
        } ?: return
        // Keeps the list from following the first item as it moves away
        if (item.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        onMove(item.index, target.index)
    }

    /** How far the held item reaches past the top or bottom edge, signed */
    fun pastEdge(): Float {
        val item = held ?: return 0f
        val top = startOffset + travel
        val bottom = top + item.size
        val info = list.layoutInfo
        return when {
            travel > 0 -> (bottom - info.viewportEndOffset).coerceAtLeast(0f)
            travel < 0 -> (top - info.viewportStartOffset).coerceAtMost(0f)
            else -> 0f
        }
    }

    fun end() {
        val key = heldKey ?: return
        val offset = heldOffset
        heldKey = null
        travel = 0f
        onDrop(key, offset)
    }
}

/** The fastest the list scrolls under a held item, in pixels a frame */
private const val MAX_SCROLL_PER_FRAME = 24f
