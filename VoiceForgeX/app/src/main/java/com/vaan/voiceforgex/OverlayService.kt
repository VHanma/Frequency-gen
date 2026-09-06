package com.vaan.voiceforgex

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var root: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate(); startForeground(41, notification()); showBubble()
    }

    private fun notification(): Notification {
        val ch = "voiceforge_overlay"
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(ch, "VoiceForge overlay", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, ch).setContentTitle("VoiceForge X overlay").setContentText("Floating cloned-voice controls active").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
    }

    private fun showBubble() {
        if (root != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(14, 14, 14, 14); setBackgroundColor(0xDD161616.toInt())
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bubble = Button(this).apply { text = "VFX"; alpha = .72f; minWidth = 0; minimumWidth = 0 }
        val close = Button(this).apply { text = "×"; alpha = .5f; minWidth = 0; minimumWidth = 0 }
        top.addView(bubble); top.addView(close); box.addView(top)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val edit = EditText(this).apply { hint = "Type a cloned line…"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFAAAAAA.toInt()); minEms = 18 }
        val speak = Button(this).apply { text = "Speak selected clone" }
        val next = Button(this).apply { text = "Next voice" }
        panel.addView(edit); panel.addView(speak); panel.addView(next); box.addView(panel)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 240; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        bubble.setOnClickListener {
            val opening = panel.visibility != View.VISIBLE
            panel.visibility = if (opening) View.VISIBLE else View.GONE
            lp.flags = if (opening) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm?.updateViewLayout(box, lp)
            if (opening) {
                edit.requestFocus()
                edit.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, 120)
            }
        }
        close.setOnClickListener { stopSelf() }
        next.setOnClickListener {
            val all = CloneRepository.all(); if (all.isNotEmpty()) {
                val idx = all.indexOfFirst { it.id == CloneRepository.selected()?.id }
                val p = all[(idx + 1).mod(all.size)]; CloneRepository.select(p.id); Toast.makeText(this, p.name, Toast.LENGTH_SHORT).show()
            }
        }
        speak.setOnClickListener {
            val p = CloneRepository.selected(); val text = edit.text.toString().trim()
            if (p != null && text.isNotEmpty()) scope.launch(Dispatchers.IO) { runCatching { CloneEngine.play(this@OverlayService, p, text) } }
        }
        bubble.setOnTouchListener(DragTouch(wm!!, box, lp))
        wm?.addView(box, lp); root = box
    }

    override fun onDestroy() { root?.let { wm?.removeView(it) }; root = null; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private class DragTouch(val wm: WindowManager, val view: View, val lp: WindowManager.LayoutParams): View.OnTouchListener {
        var x=0; var y=0; var rx=0f; var ry=0f; var down=0L
        override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
            when(e.action){
                0 -> { x=lp.x; y=lp.y; rx=e.rawX; ry=e.rawY; down=System.currentTimeMillis(); return false }
                2 -> { lp.x=x-(e.rawX-rx).toInt(); lp.y=y+(e.rawY-ry).toInt(); wm.updateViewLayout(view,lp); return true }
            }; return false
        }
    }
}
