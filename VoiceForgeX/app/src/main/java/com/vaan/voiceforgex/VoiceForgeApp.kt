package com.vaan.voiceforgex

import android.app.Application

class VoiceForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CloneRepository.init(this)
    }
}
