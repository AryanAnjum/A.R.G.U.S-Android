package com.argus.assistant

import android.app.Application

/** Holds the one [ArgusModel] for the whole app, so the screen-sharing service can reach it too. */
class ArgusApplication : Application() {
    val model: ArgusModel by lazy { ArgusModel(this) }
}
