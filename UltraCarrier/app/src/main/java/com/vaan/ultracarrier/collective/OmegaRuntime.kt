package com.vaan.ultracarrier.collective

import android.content.Context

object OmegaRuntime {
    @Volatile private var controller: OmegaController? = null

    fun get(context: Context): OmegaController {
        controller?.let { return it }
        return synchronized(this) {
            controller ?: OmegaController(context.applicationContext).also { controller = it }
        }
    }
}
