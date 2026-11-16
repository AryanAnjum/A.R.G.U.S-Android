package com.argus.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.argus.assistant.ui.ArgusScreen

/** The one screen of the app. All state lives in [ArgusModel], so it survives rotation and screen sharing. */
class MainActivity : ComponentActivity() {
    private val model: ArgusModel get() = (application as ArgusApplication).model

    private val screenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenShareService.start(this, result.resultCode, data)
        } else {
            model.system("Screen sharing was cancelled.")
        }
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askToShareScreen()  // the notification only shows the Stop button; sharing works either way
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // the screen stays on while ARGUS watches
        handleLink(intent)
        setContent {
            ArgusScreen(model = model, onShareScreen = ::shareScreen, onStopSharing = { ScreenShareService.stop(this) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** The phone's camera app or a browser opened an `argus://connect?...` link from the PC's QR code. */
    private fun handleLink(intent: Intent?) {
        val link = intent?.dataString ?: return
        if (link.startsWith("argus:", ignoreCase = true)) model.openLink(link)
    }

    override fun onStart() {
        super.onStart()
        model.camera.attach(this)
        model.setForeground(true)
    }

    override fun onStop() {
        model.camera.detach(this)
        model.setForeground(false)
        super.onStop()
    }

    private fun shareScreen() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        askToShareScreen()
    }

    private fun askToShareScreen() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            screenCapture.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            model.system("This phone doesn't allow screen sharing.")
        }
    }
}
