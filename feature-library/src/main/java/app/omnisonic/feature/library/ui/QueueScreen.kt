package app.omnisonic.feature.library.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.omnisonic.feature.library.R
import app.omnisonic.feature.library.player.PlayerSheetVelocity
import app.omnisonic.feature.library.player.QueuePullHandoff
import app.omnisonic.feature.library.queue.QueueRow
import app.omnisonic.feature.library.queue.QueueUiState
import app.omnisonic.feature.library.queue.QueueViewModel
import app.omnisonic.playback.api.queue.PlaybackQueueEntry
import app.omnisonic.provider.api.CoverImageKey
import app.omnisonic.style.GestureTwin
import app.omnisonic.style.OmnisonicTheme
import app.omnisonic.style.gestureTwins
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun QueueRoute(
    viewModel: QueueViewModel,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    sheetDrag: PlayerSheetDrag? = null,
    isRevealed: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    QueueScreen(
        state = state,
        onJump = viewModel::jumpTo,
        onRemove = viewModel::remove,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onClearRemaining = viewModel::clearQueueExceptCurrent,
        onBack = onBack,
        onOpenLibrary = onOpenLibrary,
        sheetDrag = sheetDrag,
        isRevealed = isRevealed,
    )
}

/**
 * Queue screen (UI_SPEC.md §3.9): header with the "Playing N of M" summary and
 * a clear-remaining action, then the queue in playback order with the current
 * row marked. Rows jump on tap, swipe-to-remove (twin: the row's remove
 * button), reorder through the row overflow (drag-reorder waits for the Phase
 * 9 row-action work), and the clear action keeps only the playing entry.
 *
 * Inside the player sheet ([sheetDrag] non-null) a downward drag that starts
 * at the list's start, or at the unscrolled position the reveal put the current
 * row on, hands off to the sheet and pulls now-playing back
 * (ui/player-queue-return.md §4.1, D-24). [isRevealed] is the sheet's reveal
 * edge: on each open the list lands with the current row second, and a jump
 * control appears once the current row has been scrolled away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: QueueUiState,
    onJump: (Int) -> Unit,
    onRemove: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onClearRemaining: () -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    sheetDrag: PlayerSheetDrag? = null,
    isRevealed: Boolean = true,
) {
    val tokens = OmnisonicTheme.tokens
    val listState = rememberLazyListState()
    val pull = remember { QueuePullHandoff() }
    val scope = rememberCoroutineScope()
    val currentOrderIndex = state.currentOrderIndex
    val currentRow = currentOrderIndex?.let { index -> state.rows.getOrNull(index) }
    val currentListIndex = currentRow?.let { (it.orderIndex - 1).coerceAtLeast(0) }

    // The queue layer stays composed under now-playing, so positioning has to
    // wait for the reveal edge instead of running on every track change; the
    // latch then holds the position until the sheet hides again. The same edge
    // arms the return pull at that position: the queue opens with the current
    // row second, so requiring the true list top made the gesture dead on open
    // (D-24). One user scroll reverts eligibility to true-top-only.
    var positionedForReveal by remember { mutableStateOf(false) }
    var userScrolledSinceReveal by remember { mutableStateOf(false) }
    LaunchedEffect(isRevealed, currentListIndex, state.rows.isEmpty()) {
        if (!isRevealed) {
            positionedForReveal = false
            userScrolledSinceReveal = false
            return@LaunchedEffect
        }
        if (positionedForReveal || currentListIndex == null || state.rows.isEmpty()) {
            return@LaunchedEffect
        }
        listState.scrollToItem(currentListIndex)
        positionedForReveal = true
        userScrolledSinceReveal = false
    }
    val atRevealPosition = positionedForReveal && !userScrolledSinceReveal

    val jumpTargetVisible by remember(currentOrderIndex) {
        derivedStateOf {
            val index = currentOrderIndex ?: return@derivedStateOf false
            val layout = listState.layoutInfo
            // An empty layoutInfo means the first frame before the list measured;
            // "nothing visible" there must not read as "current row is hidden".
            layout.visibleItemsInfo.isNotEmpty() && layout.visibleItemsInfo.none { item ->
                item.index == index &&
                    item.offset >= layout.viewportStartOffset &&
                    item.offset + item.size <= layout.viewportEndOffset
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = tokens.layout.listRowMinHeight)
                    .padding(horizontal = tokens.spacing.sm),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.queue_back),
                    )
                }
                Text(
                    text = state.currentPosition?.let { position ->
                        stringResource(R.string.queue_playing_of, position, state.total)
                    } ?: stringResource(R.string.queue_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = tokens.spacing.sm),
                )
                TextButton(
                    onClick = onClearRemaining,
                    enabled = state.rows.size > 1,
                ) {
                    Text(stringResource(R.string.queue_clear_remaining))
                }
            }
            if (state.isEmpty) {
                QueueEmpty(
                    onOpenLibrary = onOpenLibrary,
                    modifier = Modifier.queueEmptySheetPull(sheetDrag),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (sheetDrag == null) {
                                    Modifier
                                } else {
                                    Modifier.queueSheetHandoff(
                                        listState = listState,
                                        pull = pull,
                                        sheetDrag = sheetDrag,
                                        atRevealPosition = atRevealPosition,
                                        onUserScroll = { userScrolledSinceReveal = true },
                                    )
                                },
                            ),
                        contentPadding = PaddingValues(
                            start = tokens.layout.pagePadding,
                            top = tokens.spacing.sm,
                            end = tokens.layout.pagePadding,
                            // The jump pill floats over the list; the extra room
                            // keeps the last row clear of it (ui/player-queue-return.md §5).
                            bottom = tokens.spacing.sm + tokens.imagery.listThumbSize +
                                tokens.spacing.lg,
                        ),
                    ) {
                        items(state.rows, key = { row -> row.entry.queueItemId }) { row ->
                            QueueRowItem(
                                row = row,
                                canReorder = state.canReorder,
                                canMoveUp = state.canReorder &&
                                    state.rows.firstOrNull()?.entry?.queueItemId !=
                                    row.entry.queueItemId,
                                canMoveDown = state.canReorder && !row.isLast(state.rows),
                                onJump = onJump,
                                onRemove = onRemove,
                                onMoveUp = onMoveUp,
                                onMoveDown = onMoveDown,
                            )
                        }
                    }
                    if (jumpTargetVisible && currentRow != null) {
                        JumpToCurrent(
                            row = currentRow,
                            onClick = {
                                scope.launch { listState.animateScrollToItem(currentListIndex ?: 0) }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(tokens.spacing.md),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hands a downward drag that started at the list's start — or at the unscrolled
 * reveal position — to the sheet: the nested-scroll connection consumes the
 * pre-scroll delta (so the list stays pinned and Material never sees an
 * overscroll), and the passive observer only latches the gesture-start
 * eligibility and reports the release velocity (ui/gesture-contract.md,
 * scrollable-child handoff). Nothing here consumes pointer events, so the
 * list's own drag detection is never poisoned. A user scroll that the sheet
 * does not take reports back so the caller can drop the reveal-position
 * eligibility.
 *
 * The handoff owns a closed gesture: the observer opens it at the down and
 * closes it at the lift, and every delta in between may reach the sheet at most
 * once. A nested-scroll callback that arrives after the lift is refused by the
 * handoff and dropped by the sheet as well (D-25).
 */
@Composable
private fun Modifier.queueSheetHandoff(
    listState: LazyListState,
    pull: QueuePullHandoff,
    sheetDrag: PlayerSheetDrag?,
    atRevealPosition: Boolean,
    onUserScroll: () -> Unit,
): Modifier {
    val currentDrag = rememberUpdatedState(sheetDrag)
    val currentAtRevealPosition = rememberUpdatedState(atRevealPosition)
    val currentUserScroll = rememberUpdatedState(onUserScroll)
    val connection = remember(listState, pull, currentDrag, currentUserScroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val drag = currentDrag.value ?: return Offset.Zero
                if (!pull.shouldHandOff(available.y)) {
                    if (available.y != 0f) currentUserScroll.value()
                    return Offset.Zero
                }
                drag.onDrag(available.y)
                pull.onHandedOff()
                return Offset(0f, available.y)
            }
        }
    }
    return this
        .nestedScroll(connection)
        .pointerInput(listState, pull) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pull.onDown(
                    atTop = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0,
                    atRevealPosition = currentAtRevealPosition.value,
                )
                val tracker = PlayerSheetVelocity()
                tracker.add(down)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        pull.onCancel()
                        break
                    }
                    tracker.add(change)
                    if (!change.pressed) {
                        // onPreFling is not guaranteed once the list consumed no
                        // deltas, so the release comes from this observer.
                        if (pull.onUp()) currentDrag.value?.onRelease(tracker.velocityY())
                        break
                    }
                }
            }
        }
}

/** The empty queue is a leaf, so it can carry its own vertical pull surface. */
@Composable
private fun Modifier.queueEmptySheetPull(sheetDrag: PlayerSheetDrag?): Modifier {
    val currentDrag = rememberUpdatedState(sheetDrag)
    return if (sheetDrag == null) {
        this
    } else {
        this.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = PlayerSheetVelocity()
                tracker.add(down)
                var dx = 0f
                var dy = 0f
                var claimed = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    tracker.add(change)
                    dx += delta.x
                    dy += delta.y
                    if (!claimed) {
                        if (change.isConsumed) break
                        if (abs(dy) > abs(dx) && abs(dy) >= viewConfiguration.touchSlop) {
                            claimed = true
                        }
                    }
                    if (claimed) {
                        change.consume()
                        if (delta.y > 0f) currentDrag.value?.onDrag(delta.y)
                    }
                    if (!change.pressed) {
                        if (claimed) currentDrag.value?.onRelease(tracker.velocityY())
                        break
                    }
                }
            }
        }
    }
}

/**
 * Floating jump-to-current control: the visible twin of "open where I am" once
 * the current row has been scrolled away (ui/player-queue-return.md §5). It is
 * a plain button, so it needs no gesture twin of its own.
 */
@Composable
private fun JumpToCurrent(
    row: QueueRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = OmnisonicTheme.tokens
    val label = stringResource(R.string.queue_jump_to, row.entry.title)
    Surface(
        shape = RoundedCornerShape(tokens.shapes.pill),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
            modifier = Modifier.padding(
                horizontal = tokens.spacing.md,
                vertical = tokens.spacing.sm,
            ),
        ) {
            CoverImage(
                cover = row.entry.coverImageKey(),
                contentDescription = null,
                modifier = Modifier.size(tokens.imagery.listThumbSize),
            )
            Text(
                text = row.entry.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Filled.Equalizer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(tokens.imagery.uiIconSize),
            )
        }
    }
}

private fun QueueRow.isLast(rows: List<QueueRow>): Boolean =
    rows.lastOrNull()?.entry?.queueItemId == entry.queueItemId

@Composable
private fun QueueRowItem(
    row: QueueRow,
    canReorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onJump: (Int) -> Unit,
    onRemove: (Long) -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
) {
    val tokens = OmnisonicTheme.tokens
    val title = row.entry.title
    val removeLabel = stringResource(R.string.queue_remove, title)
    val moveUpLabel = stringResource(R.string.queue_move_up, title)
    val moveDownLabel = stringResource(R.string.queue_move_down, title)
    val twins = remember(removeLabel) {
        listOf(GestureTwin(GESTURE_QUEUE_REMOVE, removeLabel, removeLabel))
    }
    val scope = rememberCoroutineScope()
    val offset = remember(row.entry.queueItemId) { Animatable(0f) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val deleteSwipe = QueueSwipeAction(
        enabled = true,
        thresholdFraction = QueueSwipeRules.DELETE_THRESHOLD_FRACTION,
        background = MaterialTheme.colorScheme.errorContainer,
        icon = Icons.Filled.Delete,
        onDismiss = { onRemove(row.entry.queueItemId) },
    )
    val currentDismiss = rememberUpdatedState(deleteSwipe.onDismiss)
    val swipeSpring = remember(tokens.motion.nowPlayingExpand) {
        spring<Float>(
            dampingRatio = tokens.motion.nowPlayingExpand.dampingRatio,
            stiffness = tokens.motion.nowPlayingExpand.stiffness,
        )
    }
    // The start-to-end direction is reserved and stays disabled (Q2); the row
    // renders one reveal direction until that slot is wired.
    val startToEndEnabled = false
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidthPx = it.width },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(deleteSwipe.background)
                .testTag("queue-swipe-reveal-${row.entry.queueItemId}"),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = deleteSwipe.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(end = tokens.layout.pagePadding),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.layout.listRowMinHeight)
                // The offset wraps the row's own background: both must move so
                // the reveal layer underneath becomes visible.
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(row.entry.queueItemId, rowWidthPx, deleteSwipe.enabled) {
                    if (!deleteSwipe.enabled || rowWidthPx <= 0) return@pointerInput
                    val width = rowWidthPx.toFloat()
                    detectHorizontalDragGestures(
                        onDragCancel = {
                            scope.launch { offset.animateTo(0f, swipeSpring) }
                        },
                        onDragEnd = {
                            val target = QueueSwipeRules.settleTarget(
                                offsetPx = offset.value,
                                rowWidthPx = width,
                                thresholdFraction = deleteSwipe.thresholdFraction,
                            )
                            scope.launch {
                                offset.animateTo(target, swipeSpring)
                                if (target != 0f) currentDismiss.value()
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offset.snapTo(
                                QueueSwipeRules.dragOffset(
                                    current = offset.value,
                                    deltaXPx = dragAmount,
                                    rowWidthPx = width,
                                    startToEndEnabled = startToEndEnabled,
                                ),
                            )
                        }
                    }
                }
                .gestureTwins(twins) { gestureId ->
                    if (gestureId == GESTURE_QUEUE_REMOVE) onRemove(row.entry.queueItemId)
                }
                .clickable { onJump(row.orderIndex) }
                .padding(vertical = tokens.spacing.xs),
        ) {
            if (row.isCurrent) {
                Icon(
                    imageVector = Icons.Filled.Equalizer,
                    contentDescription = stringResource(R.string.queue_current_marker),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(tokens.imagery.uiIconSize),
                )
            } else {
                Spacer(Modifier.size(tokens.imagery.uiIconSize))
            }
            Spacer(Modifier.width(tokens.spacing.sm))
            CoverImage(
                cover = row.entry.coverImageKey(),
                contentDescription = null,
                modifier = Modifier.size(tokens.imagery.listThumbSize),
            )
            Spacer(Modifier.width(tokens.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.entry.artistName?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { onRemove(row.entry.queueItemId) }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = removeLabel,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.queue_row_actions, title),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(moveUpLabel) },
                        enabled = canReorder && canMoveUp,
                        onClick = {
                            menuOpen = false
                            onMoveUp(row.entry.queueItemId)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(moveDownLabel) },
                        enabled = canReorder && canMoveDown,
                        onClick = {
                            menuOpen = false
                            onMoveDown(row.entry.queueItemId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueEmpty(onOpenLibrary: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = OmnisonicTheme.tokens
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.md),
            modifier = Modifier.padding(horizontal = tokens.layout.pagePadding),
        ) {
            Text(
                text = stringResource(R.string.queue_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.queue_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenLibrary) {
                Text(stringResource(R.string.queue_open_library))
            }
        }
    }
}

private fun PlaybackQueueEntry.coverImageKey(): CoverImageKey? =
    coverImageId?.let { CoverImageKey(providerId, it) }

/**
 * One swipe direction's reveal (ui/queue-swipe.md §2): [thresholdFraction] of
 * the row width a release must pass to commit, the background and icon shown
 * behind the row, and the command run once the committed row has sprung
 * off-screen.
 */
@Immutable
data class QueueSwipeAction(
    val enabled: Boolean,
    val thresholdFraction: Float,
    val background: Color,
    val icon: ImageVector,
    val onDismiss: () -> Unit,
)

/**
 * Pure swipe rules of the queue row (ui/queue-swipe.md §2). Offsets are
 * horizontal pixels: `0` is at rest and negative is the revealed delete
 * direction. Right-to-left is the only live direction (Q2); [dragOffset]
 * accepts positive travel only when the reserved start-to-end direction is
 * enabled, so wiring it later cannot change the delete path.
 */
object QueueSwipeRules {

    /** Release past half the row commits the delete (ui/queue-swipe.md §2). */
    const val DELETE_THRESHOLD_FRACTION = 0.5f

    /** Live offset, clamped to the enabled directions. */
    fun dragOffset(
        current: Float,
        deltaXPx: Float,
        rowWidthPx: Float,
        startToEndEnabled: Boolean = false,
    ): Float {
        require(rowWidthPx > 0f) { "rowWidthPx must be positive (was $rowWidthPx)" }
        val max = if (startToEndEnabled) rowWidthPx else 0f
        return (current + deltaXPx).coerceIn(-rowWidthPx, max)
    }

    /** A release at or past the threshold commits; it is total on purpose. */
    fun commitsOnRelease(
        offsetPx: Float,
        rowWidthPx: Float,
        thresholdFraction: Float,
    ): Boolean {
        require(rowWidthPx > 0f) { "rowWidthPx must be positive (was $rowWidthPx)" }
        require(thresholdFraction > 0f) {
            "thresholdFraction must be positive (was $thresholdFraction)"
        }
        return abs(offsetPx) >= rowWidthPx * thresholdFraction
    }

    /** Off-screen settle target for a committed release, or `0` to spring back. */
    fun settleTarget(
        offsetPx: Float,
        rowWidthPx: Float,
        thresholdFraction: Float,
    ): Float = when {
        !commitsOnRelease(offsetPx, rowWidthPx, thresholdFraction) -> 0f
        offsetPx < 0f -> -rowWidthPx
        else -> rowWidthPx
    }
}

private const val GESTURE_QUEUE_REMOVE = "queue-remove"
