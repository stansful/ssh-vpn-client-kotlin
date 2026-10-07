package com.stansful.sshvpnclient.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The 44 stroke icons of the "shadow" design (BRIEF §6) plus the plain and alert shields the
 * artboards use: 24×24 viewport, stroke 1.8, round caps and joins, no fill. Use with `Icon(ShadowIcons.Home, contentDescription, tint = …)`; any tint works.
 * Size them 18–22 dp (16 in chips, 40 in the orb).
 */
object ShadowIcons {
    /** Power button. */
    val Power: ImageVector by lazy {
        strokeIcon(
            "power",
            "M12 3v8",
            "M6.3 6.8a8 8 0 1 0 11.4 0",
        )
    }

    /** Auto mode (sparkle). */
    val Auto: ImageVector by lazy {
        strokeIcon(
            "auto",
            "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z",
            "M19 15l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z",
        )
    }

    /** Server mode / SSH server (terminal). */
    val Server: ImageVector by lazy {
        strokeIcon(
            "server",
            "M6 4.5h12a3 3 0 0 1 3 3v9a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-9a3 3 0 0 1 3 -3z",
            "M7 9.5l3 2.5-3 2.5",
            "M12.5 15h4.5",
        )
    }

    /** Routes (globe). */
    val Routes: ImageVector by lazy {
        strokeIcon(
            "routes",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M3 12h18",
            "M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3z",
        )
    }

    /** Home. */
    val Home: ImageVector by lazy {
        strokeIcon(
            "home",
            "M4 10.5L12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5H15v-6h-6v6H5.5A1.5 1.5 0 0 1 4 19z",
        )
    }

    /** Settings (sliders). */
    val Settings: ImageVector by lazy {
        strokeIcon(
            "settings",
            "M4 7h10",
            "M18 7h2",
            "M14 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
            "M4 17h4",
            "M12 17h8",
            "M8 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z",
        )
    }

    /** Connection activity. */
    val Activity: ImageVector by lazy {
        strokeIcon(
            "activity",
            "M3 12h4l2.5-6 5 12 2.5-6h4",
        )
    }

    /** Chevron right (row affordance). */
    val ChevronRight: ImageVector by lazy {
        strokeIcon(
            "chevron-right",
            "M9 6l6 6-6 6",
        )
    }

    /** Chevron down. */
    val ChevronDown: ImageVector by lazy {
        strokeIcon(
            "chevron-down",
            "M6 9l6 6 6-6",
        )
    }

    /** Chevron up. */
    val ChevronUp: ImageVector by lazy {
        strokeIcon(
            "chevron-up",
            "M6 15l6-6 6 6",
        )
    }

    /** Back (chevron left). */
    val Back: ImageVector by lazy {
        strokeIcon(
            "back",
            "M15 6l-6 6 6 6",
        )
    }

    /** Check mark. */
    val Check: ImageVector by lazy {
        strokeIcon(
            "check",
            "M5 12.5l4.5 4.5L19 7.5",
        )
    }

    /** Check in a circle (success, selected). */
    val CheckCircle: ImageVector by lazy {
        strokeIcon(
            "check-circle",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M8 12.3l2.7 2.7L16 9.7",
        )
    }

    /** Plus / add. */
    val Plus: ImageVector by lazy {
        strokeIcon(
            "plus",
            "M12 5v14",
            "M5 12h14",
        )
    }

    /** Close (x). */
    val Close: ImageVector by lazy {
        strokeIcon(
            "close",
            "M6 6l12 12",
            "M18 6L6 18",
        )
    }

    /** Search. */
    val Search: ImageVector by lazy {
        strokeIcon(
            "search",
            "M4.5 11a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0z",
            "M16 16l4.5 4.5",
        )
    }

    /** More (vertical dots). */
    val More: ImageVector by lazy {
        strokeIcon(
            "more",
            "M10.8 5.5a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z",
            "M10.8 12a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z",
            "M10.8 18.5a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z",
        )
    }

    /** Pin. */
    val Pin: ImageVector by lazy {
        strokeIcon(
            "pin",
            "M9 4h6l-1 5 3 3H7l3-3z",
            "M12 15v5",
        )
    }

    /** Copy. */
    val Copy: ImageVector by lazy {
        strokeIcon(
            "copy",
            "M10.5 8h7a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-7" +
                "a2.5 2.5 0 0 1 2.5 -2.5z",
            "M16 8V5.5A1.5 1.5 0 0 0 14.5 4h-9A1.5 1.5 0 0 0 4 5.5v9A1.5 1.5 0 0 0 5.5 16H8",
        )
    }

    /** Key. */
    val Key: ImageVector by lazy {
        strokeIcon(
            "key",
            "M4 15a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z",
            "M11 12l8-8",
            "M16 7l2.5 2.5",
            "M14 9l2 2",
        )
    }

    /** Shield with check (tunnel check). */
    val Shield: ImageVector by lazy {
        strokeIcon(
            "shield",
            "M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z",
            "M9 12l2.2 2.2L15.5 10",
        )
    }

    /** Plain shield. */
    val ShieldOutline: ImageVector by lazy {
        strokeIcon(
            "shield-outline",
            "M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z",
        )
    }

    /** Shield with an exclamation mark (public-route risk, VPN permission problems). */
    val ShieldAlert: ImageVector by lazy {
        strokeIcon(
            "shield-alert",
            "M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z",
            "M12 8.5v4.5",
            "M12 16.3v.1",
        )
    }

    /** Lock. */
    val Lock: ImageVector by lazy {
        strokeIcon(
            "lock",
            "M7.5 10.5h9a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-5" +
                "a2.5 2.5 0 0 1 2.5 -2.5z",
            "M8 10.5V8a4 4 0 0 1 8 0v2.5",
        )
    }

    /** Refresh. */
    val Refresh: ImageVector by lazy {
        strokeIcon(
            "refresh",
            "M20 11a8 8 0 0 0-14.5-4.5L4 8",
            "M4 4v4h4",
            "M4 13a8 8 0 0 0 14.5 4.5L20 16",
            "M20 20v-4h-4",
        )
    }

    /** Download. */
    val Download: ImageVector by lazy {
        strokeIcon(
            "download",
            "M12 4v11",
            "M7.5 10.5L12 15l4.5-4.5",
            "M5 19.5h14",
        )
    }

    /** Delete. */
    val Trash: ImageVector by lazy {
        strokeIcon(
            "trash",
            "M4.5 7h15",
            "M9.5 7V4.5h5V7",
            "M6.5 7l1 12.5h9l1-12.5",
        )
    }

    /** Show (eye). */
    val Eye: ImageVector by lazy {
        strokeIcon(
            "eye",
            "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
        )
    }

    /** Hide (eye crossed). */
    val EyeOff: ImageVector by lazy {
        strokeIcon(
            "eye-off",
            "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
            "M4 4l16 16",
        )
    }

    /** Apps grid (app routing). */
    val Apps: ImageVector by lazy {
        strokeIcon(
            "apps",
            "M5.8 4h2.9a1.8 1.8 0 0 1 1.8 1.8v2.9a1.8 1.8 0 0 1 -1.8 1.8h-2.9a1.8 1.8 0 0 1 -1.8 -1.8" +
                "v-2.9a1.8 1.8 0 0 1 1.8 -1.8z",
            "M15.3 4h2.9a1.8 1.8 0 0 1 1.8 1.8v2.9a1.8 1.8 0 0 1 -1.8 1.8h-2.9a1.8 1.8 0 0 1 -1.8 -1.8" +
                "v-2.9a1.8 1.8 0 0 1 1.8 -1.8z",
            "M5.8 13.5h2.9a1.8 1.8 0 0 1 1.8 1.8v2.9a1.8 1.8 0 0 1 -1.8 1.8h-2.9a1.8 1.8 0 0 1 -1.8 -1.8" +
                "v-2.9a1.8 1.8 0 0 1 1.8 -1.8z",
            "M15.3 13.5h2.9a1.8 1.8 0 0 1 1.8 1.8v2.9a1.8 1.8 0 0 1 -1.8 1.8h-2.9a1.8 1.8 0 0 1 -1.8 -1.8" +
                "v-2.9a1.8 1.8 0 0 1 1.8 -1.8z",
        )
    }

    /** Clipboard / paste. */
    val Clipboard: ImageVector by lazy {
        strokeIcon(
            "clipboard",
            "M8.5 4.5h7a2.5 2.5 0 0 1 2.5 2.5v11a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-11" +
                "a2.5 2.5 0 0 1 2.5 -2.5z",
            "M9.5 4.5V3.5h5v1",
            "M9 11h6",
            "M9 15h4",
        )
    }

    /** Link. */
    val Link: ImageVector by lazy {
        strokeIcon(
            "link",
            "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1",
            "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
        )
    }

    /** Warning triangle. */
    val Warning: ImageVector by lazy {
        strokeIcon(
            "warning",
            "M12 4l9 16H3z",
            "M12 10v4",
            "M12 17.2v.1",
        )
    }

    /** Info. */
    val Info: ImageVector by lazy {
        strokeIcon(
            "info",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M12 11v5",
            "M12 7.8v.1",
        )
    }

    /** Palette (appearance). */
    val Palette: ImageVector by lazy {
        strokeIcon(
            "palette",
            "M12 3a9 9 0 1 0 0 18c1.1 0 1.5-.8 1.5-1.6 0-1.3-1-1.6-1-2.7 0-.9.7-1.7 1.7-1.7H17" +
                "a4 4 0 0 0 4-4C21 6.6 17 3 12 3z",
            "M6.5 11a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z",
            "M9 7.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z",
            "M13.5 7.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z",
        )
    }

    /** Code (source, GitHub). */
    val Code: ImageVector by lazy {
        strokeIcon(
            "code",
            "M8 8l-4 4 4 4",
            "M16 8l4 4-4 4",
            "M13.5 5l-3 14",
        )
    }

    /** Network (Wi-Fi arcs). */
    val Network: ImageVector by lazy {
        strokeIcon(
            "network",
            "M5 12.5a10 10 0 0 1 14 0",
            "M8.5 16a5 5 0 0 1 7 0",
            "M12 19.5v.1",
        )
    }

    /** Send (arrow right). */
    val Send: ImageVector by lazy {
        strokeIcon(
            "send",
            "M5 12h12",
            "M13 7l5 5-5 5",
        )
    }

    /** Filter. */
    val Filter: ImageVector by lazy {
        strokeIcon(
            "filter",
            "M4 6h16",
            "M7 12h10",
            "M10 18h4",
        )
    }

    /** Bolt (route pool, speed). */
    val Bolt: ImageVector by lazy {
        strokeIcon(
            "bolt",
            "M13 3L5 13.5h6L10 21l8-10.5h-6z",
        )
    }

    /** Clock. */
    val Clock: ImageVector by lazy {
        strokeIcon(
            "clock",
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M12 7.5V12l3 2",
        )
    }

    /** Engine (chip). */
    val Engine: ImageVector by lazy {
        strokeIcon(
            "engine",
            "M8.5 6h7a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-7" +
                "a2.5 2.5 0 0 1 2.5 -2.5z",
            "M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3",
        )
    }

    /** Fingerprint. */
    val Fingerprint: ImageVector by lazy {
        strokeIcon(
            "fingerprint",
            "M7 11a5 5 0 0 1 10 0v1.5",
            "M12 11v3.5a6 6 0 0 1-1.5 4",
            "M4.5 13.5V11a7.5 7.5 0 0 1 15 0v3",
            "M9.5 20a9 9 0 0 0 1-4.5V11",
            "M14.5 15c0 2-.4 3.6-1 5",
        )
    }

    /** Edit (pencil). */
    val Edit: ImageVector by lazy {
        strokeIcon(
            "edit",
            "M4 20h4L19 9l-4-4L4 16z",
            "M13.5 6.5l4 4",
        )
    }

    /** Upload / arrow up. */
    val Upload: ImageVector by lazy {
        strokeIcon(
            "upload",
            "M12 20V8",
            "M7 12.5L12 7.5l5 5",
        )
    }

    /** Open externally. */
    val External: ImageVector by lazy {
        strokeIcon(
            "external",
            "M14 4h6v6",
            "M20 4l-9 9",
            "M19 14v4.5A1.5 1.5 0 0 1 17.5 20h-12A1.5 1.5 0 0 1 4 18.5v-12A1.5 1.5 0 0 1 5.5 5H10",
        )
    }

    /** Every icon with its design name, for galleries and previews. */
    val all: List<Pair<String, ImageVector>> by lazy {
        listOf(
            "power" to Power,
            "auto" to Auto,
            "server" to Server,
            "routes" to Routes,
            "home" to Home,
            "settings" to Settings,
            "activity" to Activity,
            "chevron-right" to ChevronRight,
            "chevron-down" to ChevronDown,
            "chevron-up" to ChevronUp,
            "back" to Back,
            "check" to Check,
            "check-circle" to CheckCircle,
            "plus" to Plus,
            "close" to Close,
            "search" to Search,
            "more" to More,
            "pin" to Pin,
            "copy" to Copy,
            "key" to Key,
            "shield" to Shield,
            "shield-outline" to ShieldOutline,
            "shield-alert" to ShieldAlert,
            "lock" to Lock,
            "refresh" to Refresh,
            "download" to Download,
            "trash" to Trash,
            "eye" to Eye,
            "eye-off" to EyeOff,
            "apps" to Apps,
            "clipboard" to Clipboard,
            "link" to Link,
            "warning" to Warning,
            "info" to Info,
            "palette" to Palette,
            "code" to Code,
            "network" to Network,
            "send" to Send,
            "filter" to Filter,
            "bolt" to Bolt,
            "clock" to Clock,
            "engine" to Engine,
            "fingerprint" to Fingerprint,
            "edit" to Edit,
            "upload" to Upload,
            "external" to External,
        )
    }
}

/** Builds a tintable stroke-only icon from SVG path data (24×24 viewport). */
fun strokeIcon(
    name: String,
    vararg pathData: String,
    strokeWidth: Float = ICON_STROKE_WIDTH,
): ImageVector {
    val builder = ImageVector.Builder(
        name = "Shadow.$name",
        defaultWidth = ICON_SIZE.dp,
        defaultHeight = ICON_SIZE.dp,
        viewportWidth = ICON_SIZE,
        viewportHeight = ICON_SIZE,
    )
    pathData.forEach { data ->
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

private const val ICON_SIZE = 24f
private const val ICON_STROKE_WIDTH = 1.8f
