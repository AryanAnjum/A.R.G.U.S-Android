package com.argus.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.argus.assistant.ArgusModel
import com.argus.assistant.ArgusServer
import com.argus.assistant.ConnectionState
import com.argus.assistant.ReplyLanguage

private val FormBackground = Color(0xFF0B0C10)
private val FormRow = Color(0xFF1A1B21)

/** "Connect to PC": the same sections as the iPhone app's settings screen. */
@Composable
fun SettingsDialog(model: ArgusModel, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf(ArgusServer.shortAddress(model.serverUrl)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = FormBackground) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(48.dp))
                    Text("Connect to PC", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                         modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Done", color = ArgusGreen, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                         modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(8.dp))
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                       verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Section("PCs on this network")
                    Group {
                        if (model.servers.isEmpty()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = ArgusSubtext, strokeWidth = 2.dp)
                                Text("Looking for ARGUS on this network…", color = ArgusSubtext, fontSize = 15.sp)
                            }
                        }
                        for (server in model.servers) {
                            val connected = model.connection == ConnectionState.CONNECTED && server.name == model.pcName
                            FormItem(ArgusIcons.Screen, server.name, ArgusServer.shortAddress(server.urls.firstOrNull() ?: ""),
                                     trailing = if (connected) ArgusIcons.Check else null) {
                                model.connect(server)
                                onDismiss()
                            }
                        }
                    }
                    Footer("Start ARGUS on your PC and put the phone on the same Wi-Fi, or join the PC to this phone's hotspot. " +
                           "The phone sends your voice and camera; the models run on the PC.")

                    Group {
                        FormItem(ArgusIcons.QrCode, "Scan QR code", null, tint = ArgusGreen) {
                            model.startQRScan()
                            onDismiss()
                        }
                    }
                    Footer("In the PC app press Connect phone, then point this phone at the QR code.")

                    Section("Or type the PC's address")
                    Group {
                        OutlinedTextField(
                            value = address, onValueChange = { address = it }, singleLine = true,
                            placeholder = { Text("192.168.1.10") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        )
                        FormItem(null, "Connect to this address", null, tint = ArgusGreen, enabled = address.isNotBlank()) {
                            model.connectToAddress(address)
                            onDismiss()
                        }
                    }
                    Footer("It is shown in the PC app's Connect phone panel. Port 8000 is added for you.")

                    Section("Language")
                    Group {
                        for (l in ReplyLanguage.entries) {
                            FormItem(null, if (l.native == l.title) l.title else "${l.native} (${l.title})", null,
                                     trailing = if (model.language == l) ArgusIcons.Check else null) {
                                model.selectLanguage(l)
                            }
                        }
                    }
                    Footer("ARGUS speaks and answers in this language. You can also say \"speak Bengali\" or \"speak English\". " +
                           "Ask your questions in English.")

                    Section("Connected PC")
                    Group {
                        Value("Name", model.pcName.ifEmpty { "--" })
                        Value("GPU", model.metrics.gpuName.ifEmpty { "--" })
                        Value("Last reply", if (model.metrics.latency == "--") "--" else "${model.metrics.latency} ms")
                    }
                    Spacer(Modifier.height(14.dp))
                    Group {
                        FormItem(null, "Clear conversation", null, tint = Color(0xFFFF453A)) { model.clearChat() }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = ArgusSubtext, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 14.dp))
}

@Composable
private fun Footer(text: String) {
    Text(text, color = ArgusSubtext, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FormRow), content = content)
}

@Composable
private fun FormItem(icon: ImageVector?, title: String, subtitle: String?, tint: Color = Color.White,
                     trailing: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = ArgusGreen, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) tint else ArgusSubtext, fontSize = 16.sp)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = ArgusSubtext, fontSize = 12.sp)
        }
        if (trailing != null) Icon(trailing, null, tint = ArgusGreen, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Value(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, color = ArgusSubtext, fontSize = 16.sp)
    }
}
