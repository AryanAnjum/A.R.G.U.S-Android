package com.argus.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icons: simple 24 x 24 line drawings, the same family as the PC dashboard's.
 * Each string is ordinary SVG path data.
 */
object ArgusIcons {
    private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList()): ImageVector {
        val builder = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
                                          viewportWidth = 24f, viewportHeight = 24f)
        for (path in strokes) {
            builder.addPath(pathData = addPathNodes(path), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        for (path in fills) builder.addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
        return builder.build()
    }

    val Screen: ImageVector by lazy { icon("Screen", listOf("M4 3 H20 A2 2 0 0 1 22 5 V15 A2 2 0 0 1 20 17 H4 A2 2 0 0 1 2 15 V5 A2 2 0 0 1 4 3 Z", "M8 21 h8 M12 17 v4")) }
    val Eye: ImageVector by lazy { icon("Eye", listOf("M2 12 s3.5 -7 10 -7 s10 7 10 7 s-3.5 7 -10 7 S2 12 2 12 Z", "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0 Z")) }
    val Walk: ImageVector by lazy { icon("Walk", listOf("M10 4.5 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0 Z", "M12 7 v6 M12 13 l-3 8 M12 13 l3 8 M7 10 h10")) }
    val Hand: ImageVector by lazy { icon("Hand", listOf("M18 11 V6 a2 2 0 0 0 -4 0 v5 M14 10 V4 a2 2 0 0 0 -4 0 v6 M10 10.5 V6 a2 2 0 0 0 -4 0 v8", "M18 8 a2 2 0 1 1 4 0 v6 a8 8 0 0 1 -8 8 h-2 c-2.8 0 -4.5 -0.9 -5.9 -2.4 L3.4 16.9 a2 2 0 0 1 2.8 -2.9 L8 15.8")) }
    val Gear: ImageVector by lazy { icon("Gear", listOf("M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0 Z", "M12 2 l1.2 2.6 a7.6 7.6 0 0 1 2.3 0.95 l2.7 -1 l1.25 1.25 l-1 2.7 a7.6 7.6 0 0 1 0.95 2.3 L22 12 l-2.6 1.2 a7.6 7.6 0 0 1 -0.95 2.3 l1 2.7 l-1.25 1.25 l-2.7 -1 a7.6 7.6 0 0 1 -2.3 0.95 L12 22 l-1.2 -2.6 a7.6 7.6 0 0 1 -2.3 -0.95 l-2.7 1 l-1.25 -1.25 l1 -2.7 a7.6 7.6 0 0 1 -0.95 -2.3 L2 12 l2.6 -1.2 a7.6 7.6 0 0 1 0.95 -2.3 l-1 -2.7 l1.25 -1.25 l2.7 1 a7.6 7.6 0 0 1 2.3 -0.95 Z")) }
    val Video: ImageVector by lazy { icon("Video", listOf("M4 6 H14 A2 2 0 0 1 16 8 V16 A2 2 0 0 1 14 18 H4 A2 2 0 0 1 2 16 V8 A2 2 0 0 1 4 6 Z", "M16 10 l6 -3 v10 l-6 -3 Z")) }
    val VideoOff: ImageVector by lazy { icon("VideoOff", listOf("M4 6 H14 A2 2 0 0 1 16 8 V16 A2 2 0 0 1 14 18 H4 A2 2 0 0 1 2 16 V8 A2 2 0 0 1 4 6 Z", "M16 10 l6 -3 v10 l-6 -3 Z", "M3 3 L21 21")) }
    val Scan: ImageVector by lazy { icon("Scan", listOf("M3 7 V5 a2 2 0 0 1 2 -2 h2 M17 3 h2 a2 2 0 0 1 2 2 v2 M21 17 v2 a2 2 0 0 1 -2 2 h-2 M7 21 H5 a2 2 0 0 1 -2 -2 v-2", "M7 9 h10 M7 12 h10 M7 15 h6")) }
    val Mic: ImageVector by lazy { icon("Mic", listOf("M12 2 H12 A3 3 0 0 1 15 5 V11 A3 3 0 0 1 12 14 H12 A3 3 0 0 1 9 11 V5 A3 3 0 0 1 12 2 Z", "M5 10 v1 a7 7 0 0 0 14 0 v-1 M12 18 v4")) }
    val MicOff: ImageVector by lazy { icon("MicOff", listOf("M2 2 L22 22 M9 9 v2 a3 3 0 0 0 5.1 2.1 M15 9.3 V5 a3 3 0 0 0 -5.9 -0.6", "M17 16.9 A7 7 0 0 1 5 11 v-1 M19 10 v1 a7 7 0 0 1 -0.1 1.2 M12 18 v4")) }
    val Stop: ImageVector by lazy { icon("Stop", emptyList(), listOf("M7.5 6 H16.5 A1.5 1.5 0 0 1 18 7.5 V16.5 A1.5 1.5 0 0 1 16.5 18 H7.5 A1.5 1.5 0 0 1 6 16.5 V7.5 A1.5 1.5 0 0 1 7.5 6 Z")) }
    val ArrowUp: ImageVector by lazy { icon("ArrowUp", listOf("M12 19 V5 M5 12 l7 -7 l7 7")) }
    val Close: ImageVector by lazy { icon("Close", listOf("M18 6 L6 18 M6 6 l12 12")) }
    val ChevronDown: ImageVector by lazy { icon("ChevronDown", listOf("M6 9 l6 6 l6 -6")) }
    val Check: ImageVector by lazy { icon("Check", listOf("M5 12.5 l4.5 4.5 L19 7.5")) }
    val QrCode: ImageVector by lazy { icon("QrCode", listOf("M3 7 V5 a2 2 0 0 1 2 -2 h2 M17 3 h2 a2 2 0 0 1 2 2 v2 M21 17 v2 a2 2 0 0 1 -2 2 h-2 M7 21 H5 a2 2 0 0 1 -2 -2 v-2", "M8 8 h3 v3 h-3 Z M13 13 h3 v3 h-3 Z M13 8 h3 M8 13 v3 h1")) }
    val Bubble: ImageVector by lazy { icon("Bubble", listOf("M21 11.5 a8.5 8 0 0 1 -12.6 7 L3.5 20 l1.4 -4.4 A8.5 8 0 1 1 21 11.5 Z", "M8 10 h8 M8 13.5 h5")) }
    val Camera: ImageVector by lazy { icon("Camera", listOf("M4 8 h3 l2 -3 h6 l2 3 h3 a1 1 0 0 1 1 1 v9 a1 1 0 0 1 -1 1 H4 a1 1 0 0 1 -1 -1 V9 a1 1 0 0 1 1 -1 Z", "M8.5 13.5 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0 Z")) }
    val Phone: ImageVector by lazy { icon("Phone", listOf("M8.5 2 H15.5 A2.5 2.5 0 0 1 18 4.5 V19.5 A2.5 2.5 0 0 1 15.5 22 H8.5 A2.5 2.5 0 0 1 6 19.5 V4.5 A2.5 2.5 0 0 1 8.5 2 Z", "M10.5 18.5 h3")) }
    val PhoneShare: ImageVector by lazy { icon("PhoneShare", listOf("M8.5 2 H15.5 A2.5 2.5 0 0 1 18 4.5 V19.5 A2.5 2.5 0 0 1 15.5 22 H8.5 A2.5 2.5 0 0 1 6 19.5 V4.5 A2.5 2.5 0 0 1 8.5 2 Z", "M12 15 V8 M9 11 l3 -3 l3 3")) }
    val HeadCamera: ImageVector by lazy { icon("HeadCamera", listOf("M5 21 v-1 a7 7 0 0 1 14 0 v1", "M8 9 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0 Z", "M10.5 1.5 H13.5 A1 1 0 0 1 14.5 2.5 V4 A1 1 0 0 1 13.5 5 H10.5 A1 1 0 0 1 9.5 4 V2.5 A1 1 0 0 1 10.5 1.5 Z")) }
}
