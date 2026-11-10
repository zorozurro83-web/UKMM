package com.ukmm.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

object Native {
    init { System.loadLibrary("uk_android") }
    @JvmStatic external fun version(): String
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = TextView(this)
        t.textSize = 20f
        t.setPadding(48, 96, 48, 48)
        t.text = try { Native.version() } catch (e: Throwable) { "Gagal memuat library: $e" }
        setContentView(t)
    }
}
