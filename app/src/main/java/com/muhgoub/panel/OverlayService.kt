package com.muhgoub.panel

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "panel_overlay"
        const val NOTIF_ID = 1
        const val TARGET_GAME = "com.tencent.ig"
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var rootView: View? = null

    private var bubble: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panelShown = false
    private var snapAnim: ValueAnimator? = null
    private var gamePid: Int = -1
    private lateinit var sp: SharedPreferences

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hide_capture") applySecureFlag()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sp = getSharedPreferences("panel_prefs", Context.MODE_PRIVATE)
        startForeground(NOTIF_ID, buildNotification())
        checkGamePidAndRoot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        snapAnim?.cancel()
        rootView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        rootView = null
        bubble = null
        panelShown = false
        super.onDestroy()
    }

    private fun checkGamePidAndRoot() {
        Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof $TARGET_GAME"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readLine()
                if (!output.isNullOrEmpty()) {
                    gamePid = output.trim().split(" ").toInt()
                }
            } catch (e: Exception) {
                gamePid = -1
            }
        }.start()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MUHGOUB", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setContentTitle("MUHGOUB")
            .setContentText("لوحة التحكم تعمل بالروت")
            // 🟢 تم استبدال الأيقونة المفقودة بأيقونة النظام الافتراضية لمنع الأحمر
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun mm(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics
    ).toInt()

    private fun showOverlay() {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog)
        val inflater = LayoutInflater.from(themed)
        val view = inflater.inflate(R.layout.overlay_menu, null)

        val w = mm(60f).coerceAtMost(resources.displayMetrics.widthPixels)
        val h = mm(70f)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - w) / 2
            y = dp(120)
        }

        if (sp.getBoolean("hide_capture", false)) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        view.findViewById<View>(R.id.btn_close).setOnClickListener { hidePanel() }
        setupDrag(view.findViewById(R.id.header))

        val content = view.findViewById<LinearLayout>(R.id.content)
        buildChecks(themed, inflater, content)
        addSpace(themed, content, 14)
        buildGroup(themed, content, "grp1", listOf("محجوب", "تامر", "Off"))
        addSpace(themed, content, 10)
        buildGroup(themed, content, "grp2", listOf("احمد", "كريم", "عمو", "Off"))
        addSpace(themed, content, 10)

        createBubble()

        wm.addView(view, params)
        rootView = view
        panelShown = true
        bubble?.let { wm.addView(it, bubbleParams) }
        sp.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun togglePanel() {
        if (panelShown) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (!panelShown && rootView != null) {
            rootView?.visibility = View.VISIBLE
            panelShown = true
        }
    }

    private fun hidePanel() {
        if (panelShown && rootView != null) {
            rootView?.visibility = View.GONE
            panelShown = false
        }
    }

    private fun applySecureFlag() {
        val hide = sp.getBoolean("hide_capture", false)
        params.flags = if (hide) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        rootView?.let { wm.updateViewLayout(it, params) }

        if (::bubbleParams.isInitialized) {
            bubbleParams.flags = if (hide) {
                bubbleParams.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                bubbleParams.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            bubble?.let { wm.updateViewLayout(it, bubbleParams) }
        }
    }

    private fun createBubble() {
        val dm = resources.displayMetrics
        val size = dp(52)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        bubbleParams = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - dp(6)
            y = dp(60)
        }

        if (sp.getBoolean("hide_capture", false)) {
            bubbleParams.flags = bubbleParams.flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        val b = ImageView(this).apply {
            // 🟢 استخدام أيقونة افتراضية آمنة من أندرويد لتفادي خطأ الـ Drawable الناقص
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.parseColor("#80000000"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { togglePanel() }
        }
        setupBubbleTouch(b)
        bubble = b
    }

    private fun setupBubbleTouch(view: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams.x = startX + (e.rawX - touchX).toInt()
                    bubbleParams.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(view, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(e.rawX - touchX) < 5 && Math.abs(e.rawY - touchY) < 5) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDrag(header: View) {
        var startX = 0
        var startY = 0
