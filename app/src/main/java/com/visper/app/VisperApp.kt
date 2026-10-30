package com.visper.app

import android.app.Application

class VisperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: VisperApp
            private set
    }
}
