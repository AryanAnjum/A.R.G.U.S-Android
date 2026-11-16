package com.argus.assistant.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.argus.assistant.ArgusMode
import com.argus.assistant.ArgusModel
import com.argus.assistant.CameraSource
import com.argus.assistant.ChatMessage
import com.argus.assistant.ConnectionState
import com.argus.assistant.ReplyLanguage
import com.argus.assistant.Sender

val ArgusGreen = Color(0xFF00E676)
val ArgusTeal = Color(0xFF1DE9B6)
val ArgusSubtext = Color(0xFF8A8A99)
val ArgusWarn = Color(0xFFFF6B4A)
val Glass = Color(0xB80D0D12)
val ArgusGradient = Brush.horizontalGradient(listOf(ArgusGreen, ArgusTeal))

/** The whole app: the same layout as the iPhone app. */
@Composable
fun ArgusScreen(model: ArgusModel, onShareScreen: () -> Unit, onStopSharing: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.permissionResult(it)
    }
    val request = model.permissionRequest
    LaunchedEffect(request) { if (request != null) permissions.launch(request.toTypedArray()) }

    MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, primary = ArgusGreen)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Background(model)
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp)
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Header(model) { showSettings = true }
                ModePicker(model)
                when {
                    model.scanningQR -> ScanBanner(model)
                    model.mode == ArgusMode.SCREEN -> ScreenPanel(model, onShareScreen, onStopSharing)
                    else -> CameraPicker(model)
                }
                if (!model.scanningQR && model.mode == ArgusMode.SLT) SignPanel(model)
                Spacer(Modifier.weight(1f))
                Transcript(model)
                InputRow(model)
                Controls(model)
            }
        }
        if (showSettings) SettingsDialog(model) { showSettings = false }
    }
}

@Composable
private fun Background(model: ArgusModel) {
    val headView = !model.scanningQR && model.mode != ArgusMode.SCREEN && model.cameraEnabled &&
        model.cameraSource == CameraSource.HEAD
    val ownView = model.lensRunning && (model.scanningQR || (model.mode != ArgusMode.SCREEN && !headView))
    // The preview always exists so the camera has somewhere to draw; it is covered when not in use.
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                model.camera.setSurfaceProvider(surfaceProvider)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    val frame = model.remoteFrame
    when {
        headView && frame != null -> Image(frame, "Head-mount camera", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        ownView -> {}
        else -> {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF080A0F), Color.Black))),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (icon, text) = when {
                        model.mode == ArgusMode.SCREEN -> ArgusIcons.PhoneShare to
                            (if (model.screenSharing) "ARGUS can see this phone's screen" else "Screen mode looks at this phone's screen")
                        headView -> ArgusIcons.HeadCamera to model.headNote.ifEmpty { "Waiting for the head-mount camera…" }
                        else -> ArgusIcons.Camera to "Camera is off"
                    }
                    GradientIcon(icon, 44.dp)
                    Text(text, color = ArgusSubtext, fontSize = 13.sp)
                }
            }
        }
    }
    if (headView && frame != null || ownView) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.55f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f),
        )))
    }
}

@Composable
private fun GradientIcon(icon: ImageVector, size: Dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = ArgusGreen, modifier = Modifier.size(size))
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Glass)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier),
        content = content,
    )
}

// region Header

@Composable
private fun Header(model: ArgusModel, onSettings: () -> Unit) {
    GlassCard(Modifier.padding(top = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("A.R.G.U.S.", style = TextStyle(brush = ArgusGradient, fontSize = 22.sp, fontWeight = FontWeight.Black))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val dot = when (model.connection) {
                        ConnectionState.CONNECTED -> ArgusGreen
                        ConnectionState.CONNECTING -> Color.Yellow
                        ConnectionState.DISCONNECTED -> Color.Red
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Text(statusText(model), color = ArgusSubtext, fontSize = 12.sp, maxLines = 1)
                }
            }
            val m = model.metrics
            Metric("VRAM", "${m.vramUsed}/${m.vramTotal}G")
            Metric("GPU", if (m.temperature == "--") "--" else "${m.temperature}°")
            Metric("Reply", if (m.latency == "--") "--" else "${m.latency}ms")
            LanguageChip(model)
            Icon(
                ArgusIcons.Gear, "Settings", tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onSettings),
            )
        }
    }
}

/** EN / বাং: the language ARGUS speaks. Tap to switch (or say "speak Bengali" / "speak English"). */
@Composable
private fun LanguageChip(model: ArgusModel) {
    val current = model.language
    val other = if (current == ReplyLanguage.ENGLISH) ReplyLanguage.BENGALI else ReplyLanguage.ENGLISH
    Text(
        current.short, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(CircleShape).border(1.dp, ArgusTeal.copy(alpha = 0.6f), CircleShape)
            .clickable { model.selectLanguage(other) }.padding(horizontal = 9.dp, vertical = 5.dp)
            .semantics { contentDescription = "ARGUS speaks ${current.title}. Tap to switch to ${other.title}." },
    )
}

private fun statusText(model: ArgusModel): String = when (model.connection) {
    ConnectionState.CONNECTED -> if (model.guiding) "Connected • Guiding" else "Connected • ${model.mode.title}"
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.DISCONNECTED -> "Offline • tap ⚙ to connect to your PC"
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Text(label, color = ArgusSubtext, fontSize = 9.sp)
    }
}

@Composable
private fun ScanBanner(model: ArgusModel) {
    GlassCard(border = ArgusGreen.copy(alpha = 0.5f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(ArgusIcons.QrCode, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Point the camera at the QR code", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Text("On the PC, press Connect phone to show it.", color = ArgusSubtext, fontSize = 13.sp)
            Pill("Cancel", filled = true) { model.stopQRScan() }
        }
    }
}

// endregion
// region Modes and cameras

@Composable
private fun ModePicker(model: ArgusModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in ArgusMode.entries) {
            if (m == ArgusMode.SLT && !model.sltAvailable) continue
            val selected = model.mode == m
            Row(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (selected) ArgusGradient else SolidColor(Glass))
                    .clickable { model.selectMode(m) }.padding(vertical = 8.dp)
                    .semantics { contentDescription = "${m.title} mode" + if (selected) ", selected" else "" },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (m) {
                    ArgusMode.SCREEN -> ArgusIcons.Phone
                    ArgusMode.LIVE -> ArgusIcons.Eye
                    ArgusMode.ASSISTIVE -> ArgusIcons.Walk
                    ArgusMode.SLT -> ArgusIcons.Hand
                }
                val color = if (selected) Color.Black else Color.White
                Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(m.title, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Back camera, front camera, or the head-mount camera plugged into the PC. */
@Composable
private fun CameraPicker(model: ArgusModel) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(Glass).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (source in CameraSource.entries) {
            val selected = model.cameraEnabled && model.cameraSource == source
            val icon = if (source == CameraSource.HEAD) ArgusIcons.HeadCamera else ArgusIcons.Camera
            Row(
                Modifier.weight(1f).clip(CircleShape)
                    .background(if (selected) ArgusGreen.copy(alpha = 0.9f) else Color.Transparent)
                    .clickable { model.selectCamera(source) }.padding(vertical = 7.dp)
                    .semantics { contentDescription = "${source.title} camera" + if (selected) ", selected" else "" },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) Color.Black else Color.White
                Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(source.title, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Screen mode: ARGUS looks at this phone's own screen, never the camera. */
@Composable
private fun ScreenPanel(model: ArgusModel, onShare: () -> Unit, onStop: () -> Unit) {
    GlassCard(border = ArgusTeal.copy(alpha = 0.35f)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(ArgusIcons.PhoneShare, null, tint = ArgusTeal, modifier = Modifier.size(16.dp))
                Text(
                    if (model.screenSharing) "Sharing this phone's screen" else "Screen mode uses this phone's screen",
                    color = ArgusTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                if (model.screenSharing) "Open any app and say \"Hey Argus, what's on my screen?\""
                else "Share the screen, then open any app and ask about it. The camera is not used.",
                color = Color.White, fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (model.screenSharing) Pill("Stop sharing", icon = ArgusIcons.Close, onClick = onStop)
                else Pill("Share screen", icon = ArgusIcons.PhoneShare, filled = true, onClick = onShare)
            }
        }
    }
}

// endregion
// region Sign language

@Composable
private fun SignPanel(model: ArgusModel) {
    GlassCard(border = ArgusTeal.copy(alpha = 0.35f)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ArgusIcons.Hand, null, tint = ArgusTeal, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(model.signStatus, color = ArgusTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                LanguageMenu(model)
            }
            val caption = model.signCaption
            Text(
                caption.ifEmpty { "Sign in front of the camera, then pause." },
                color = if (caption.isEmpty()) ArgusSubtext else Color.White,
                fontSize = if (caption.isEmpty()) 15.sp else 24.sp, fontWeight = FontWeight.Bold,
            )
            if (model.signCaptionEnglish.isNotEmpty()) {
                Text(model.signCaptionEnglish, color = ArgusGreen, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Translate now", icon = ArgusIcons.Bubble) { model.signControl("translate") }
                Pill("Clear", icon = ArgusIcons.Close) { model.signControl("clear") }
            }
        }
    }
}

@Composable
private fun LanguageMenu(model: ArgusModel) {
    var open by remember { mutableStateOf(false) }
    val current = model.signLanguages.firstOrNull { it.code == model.signLanguage }?.signLanguage ?: model.signLanguage.uppercase()
    Box {
        Row(
            Modifier.clip(CircleShape).border(1.dp, ArgusTeal.copy(alpha = 0.6f), CircleShape).clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Icon(ArgusIcons.ChevronDown, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (l in model.signLanguages) {
                DropdownMenuItem(
                    text = { Text(if (l.ready) l.signLanguage else "${l.signLanguage} (not ready)") },
                    enabled = l.ready,
                    onClick = {
                        open = false
                        model.selectSignLanguage(l.code)
                    },
                )
            }
        }
    }
}

@Composable
private fun Pill(title: String, icon: ImageVector? = null, filled: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (filled) ArgusGreen else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (filled) Color.Black else Color.White
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// endregion
// region Transcript

@Composable
private fun Transcript(model: ArgusModel) {
    val list = rememberLazyListState()
    val count = model.messages.size
    val lastLength = model.messages.lastOrNull()?.let { it.text.length + it.translation.length } ?: 0
    LaunchedEffect(count, lastLength, model.partial, model.thinking) {
        val total = list.layoutInfo.totalItemsCount
        if (total > 0) list.animateScrollToItem(total - 1)
    }
    Box(
        Modifier.fillMaxWidth().heightIn(max = if (model.mode == ArgusMode.SLT) 170.dp else 300.dp)
            .clip(RoundedCornerShape(18.dp)).background(Glass),
    ) {
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(model.messages, key = { it.id }) { Bubble(it) }
            if (model.thinking) {
                item(key = "thinking") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(color = ArgusGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Text("Thinking…", color = ArgusSubtext, fontSize = 12.sp)
                    }
                }
            }
            if (model.partial.isNotEmpty()) {
                item(key = "partial") {
                    Text(model.partial, color = Color.White.copy(alpha = 0.6f), fontStyle = FontStyle.Italic, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    when (m.sender) {
        Sender.SYSTEM -> Text(m.text, color = ArgusSubtext, fontSize = 12.sp)
        Sender.YOU -> Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f).widthIn(min = 40.dp))
            Text(
                m.text, color = Color.Black, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(ArgusGreen).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Sender.ARGUS -> Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).clip(RoundedCornerShape(14.dp))
                    .background(if (m.isWarning) ArgusWarn.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    if (m.isWarning) "⚠ A.R.G.U.S." else "A.R.G.U.S.", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (m.isWarning) ArgusWarn else ArgusTeal,
                )
                SelectionContainer {
                    if (m.translation.isEmpty()) Text(m.text.ifEmpty { "…" }, color = Color.White, fontSize = 15.sp)
                    else Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(m.translation, color = Color.White, fontSize = 16.sp)   // what was spoken, in Bangla
                        Text(m.text, color = ArgusSubtext, fontSize = 12.sp)          // the English it came from
                    }
                }
            }
            Spacer(Modifier.width(40.dp))
        }
    }
}

// endregion
// region Input and controls

@Composable
private fun InputRow(model: ArgusModel) {
    var typed by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val send = {
        model.sendTyped(typed)
        typed = ""
        focus.clearFocus()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "OCR", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (model.ocrMode) Color.Black else Color.White,
            modifier = Modifier.clip(CircleShape).background(if (model.ocrMode) ArgusGreen else Glass)
                .clickable { model.ocrMode = !model.ocrMode }.padding(horizontal = 10.dp, vertical = 9.dp)
                .semantics { contentDescription = if (model.ocrMode) "OCR on" else "OCR off" },
        )
        BasicTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(ArgusGreen),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.clip(CircleShape).background(Glass).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (typed.isEmpty()) Text("Ask ARGUS…", color = ArgusSubtext, fontSize = 16.sp)
                    inner()
                }
            },
        )
        val canSend = typed.isNotBlank()
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (canSend) ArgusGradient else SolidColor(Glass))
                .clickable(enabled = canSend) { send() }.semantics { contentDescription = "Send" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ArgusIcons.ArrowUp, null, tint = if (canSend) Color.Black else ArgusSubtext, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Controls(model: ArgusModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        val cameraOn = model.cameraEnabled && model.mode != ArgusMode.SCREEN
        Control(if (cameraOn) ArgusIcons.Video else ArgusIcons.VideoOff, "Camera", cameraOn) { model.toggleCamera() }
        Control(ArgusIcons.Scan, "Read this", false) { model.readThis() }
        Box(
            Modifier.size(72.dp)
                .shadow(if (model.voiceOn) 14.dp else 0.dp, CircleShape, ambientColor = ArgusGreen, spotColor = ArgusGreen)
                .clip(CircleShape).background(if (model.voiceOn) ArgusGradient else SolidColor(Glass))
                .clickable { model.toggleVoice() }
                .semantics { contentDescription = if (model.voiceOn) "Voice on" else "Voice off" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (model.voiceOn) ArgusIcons.Mic else ArgusIcons.MicOff, null,
                 tint = if (model.voiceOn) Color.Black else Color.White, modifier = Modifier.size(28.dp))
        }
        Control(ArgusIcons.Walk, if (model.guiding) "Stop guide" else "Guide", model.guiding) { model.toggleGuide() }
        Control(ArgusIcons.Stop, "Stop", false) { model.stopTalking() }
    }
}

@Composable
private fun Control(icon: ImageVector, title: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(2.dp)
            .semantics { contentDescription = title },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(if (on) ArgusGreen.copy(alpha = 0.9f) else Glass),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        }
        Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

// endregion
