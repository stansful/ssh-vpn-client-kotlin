package com.stansful.sshvpnclient.ui.system

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stansful.sshvpnclient.ui.designsystem.IconTile
import com.stansful.sshvpnclient.ui.designsystem.ListGroup
import com.stansful.sshvpnclient.ui.designsystem.PrimaryButton
import com.stansful.sshvpnclient.ui.designsystem.SecondaryButton
import com.stansful.sshvpnclient.ui.designsystem.ShadowBottomSheet
import com.stansful.sshvpnclient.ui.designsystem.fadeUpIn
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowMotion
import com.stansful.sshvpnclient.ui.theme.shadowTween
import kotlinx.coroutines.launch

/**
 * Bottom sheet of the System.dc.html consent / VPN-permission boards: no title bar, scrollable
 * [content] with 20 dp sides, then "Continue" over "Not now" and a centered [footnote].
 *
 * The shell drops the sheet from composition as soon as a callback runs, so a button first slides the
 * sheet out (instant under reduced motion) and then reports. Exactly one of [onContinue] / [onNotNow]
 * runs; Back and a tap outside count as "Not now", or deliver a choice whose exit they interrupted.
 * A drag that stops the exit and lets the sheet settle open again cancels the choice: the buttons
 * work again and Back means "Not now".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SystemSheet(
    footnote: String,
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val reducedMotion = Shadow.reducedMotion
    val decision = remember { SheetDecision() }
    LaunchedEffect(sheetState) {
        snapshotFlow {
            sheetState.currentValue == SheetValue.Expanded && sheetState.targetValue == SheetValue.Expanded
        }.collect { settledOpen ->
            if (settledOpen) decision.pending = null
        }
    }
    val decide: (() -> Unit) -> Unit = { callback ->
        if (!decision.delivered && decision.pending == null) {
            if (reducedMotion) {
                decision.deliver(callback)
            } else {
                decision.pending = callback
                scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
                    if (cause == null) decision.deliver(callback)
                }
            }
        }
    }
    ShadowBottomSheet(
        onDismissRequest = { decision.deliver(decision.pending ?: onNotNow) },
        sheetState = sheetState,
        showClose = false,
        actions = {
            PrimaryButton(
                text = "Continue",
                onClick = { decide(onContinue) },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = "Not now",
                onClick = { decide(onNotNow) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = footnote,
                style = Shadow.type.caption,
                color = Shadow.colors.ink3,
                textAlign = TextAlign.Center,
                // 8 dp of the actions column + 2 = the artboard's 10 dp.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        },
    ) {
        val scroll = rememberScrollState()
        Box(modifier = Modifier.weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp),
                content = content,
            )
            // Small phones: the content scrolls under the sticky buttons ("Don't show this again" can
            // sit below the fold). While there is more below, its foot fades into the sheet over a
            // line-2 hairline, like a top bar's divider over scrolled content.
            val colors = Shadow.colors
            val sheet = colors.sheet
            val more by animateFloatAsState(
                targetValue = if (scroll.canScrollForward) 1f else 0f,
                animationSpec = shadowTween(ShadowMotion.Small, ShadowMotion.Ease),
                label = "sheet-scroll-more",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(SCROLL_FADE)
                    .graphicsLayer { alpha = more }
                    .background(Brush.verticalGradient(listOf(sheet.copy(alpha = 0f), sheet)))
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(
                            color = colors.line2,
                            start = Offset(0f, size.height - stroke / 2),
                            end = Offset(size.width, size.height - stroke / 2),
                            strokeWidth = stroke,
                        )
                    },
            )
        }
    }
}

private class SheetDecision {
    var pending: (() -> Unit)? = null
    var delivered = false

    fun deliver(callback: () -> Unit) {
        if (delivered) return
        delivered = true
        callback()
    }
}

/** 52 dp amber tile, the sheet title (titleL, 16 dp below) and the ink-2 body (8 dp below). */
@Composable
internal fun SystemSheetHeader(icon: ImageVector, title: String, body: String) {
    val colors = Shadow.colors
    // The design's handle has 18 dp below it; ShadowBottomSheet draws 14.
    Spacer(Modifier.height(4.dp))
    IconTile(
        icon = icon,
        size = 52.dp,
        iconSize = 26.dp,
        tint = colors.amberText,
        container = colors.amberTint,
        cornerRadius = 16.dp,
    )
    Text(
        text = title,
        style = Shadow.type.titleL,
        color = colors.ink1,
        modifier = Modifier
            .padding(top = 16.dp)
            .semantics {
                heading()
                paneTitle = title
            },
    )
    Text(
        text = body,
        style = Shadow.type.body,
        color = colors.ink2,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** One row of [SystemSheetList]: a 28–34 dp [leading] marker, a 14/20 title and a 12/16 caption. */
internal class SystemSheetItem(
    val title: String,
    val caption: String,
    val leading: @Composable () -> Unit,
)

/**
 * The sheet's surface-1 list group (16 dp below the body): rows 11/14 dp padding, 12 dp gap, line-2
 * dividers inset 14 dp from the inner edge. Each row reads as one TalkBack item and fades up in turn,
 * once the sheet is rising (the artboard starts the rows ~220 ms into the sheet's entrance).
 */
@Composable
internal fun SystemSheetList(items: List<SystemSheetItem>) {
    ListGroup(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        dividerInset = 14.dp,
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fadeUpIn(ROW_STAGGER_START + index)
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.leading()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = Shadow.type.segment.copy(lineHeight = 20.sp),
                        color = Shadow.colors.ink1,
                    )
                    Text(
                        text = item.caption,
                        style = Shadow.type.caption,
                        color = Shadow.colors.ink3,
                    )
                }
            }
        }
    }
}

/** 34 dp surface-2 tile with an 18 dp ink-2 icon (consent list). */
@Composable
internal fun SystemSheetIconMarker(icon: ImageVector) {
    IconTile(icon = icon, size = 34.dp, iconSize = 18.dp, cornerRadius = 12.dp)
}

/** 28 dp numbered circle (VPN steps): the current step amber, the next one neutral. */
@Composable
internal fun SystemSheetStepMarker(number: Int, current: Boolean) {
    val colors = Shadow.colors
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(color = if (current) colors.amberTint else colors.surface3, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = Shadow.type.monoS.copy(fontWeight = FontWeight.Medium),
            color = if (current) colors.amberText else colors.ink2,
        )
    }
}

/** 13/18 ink-3 note with a 16 dp info icon (12 dp below the list, 4 dp sides). */
@Composable
internal fun SystemSheetNote(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Shadow.colors.ink3,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Text(
            text = text,
            style = Shadow.type.bodyS,
            color = Shadow.colors.ink3,
            modifier = Modifier.weight(1f),
        )
    }
}

/** fadeUpIn stagger slot of the first list row: slots 6, 7, 8 start 180 / 210 / 240 ms in. */
private const val ROW_STAGGER_START = 6
private val SCROLL_FADE = 24.dp
