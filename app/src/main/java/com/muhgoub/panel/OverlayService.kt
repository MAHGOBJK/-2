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
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "panel_overlay"
        const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var rootView: View? = null

    // الأيقونة العايمة اللي بتظهر لما القايمة تتقفل مؤقتا
    private var bubble: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panelShown = false
    private var snapAnim: ValueAnimator? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hide_capture") applySecureFlag()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        snapAnim?.cancel()
        rootView?.animate()?.cancel()
        rootView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        rootView = null
        bubble = null
        panelShown = false
        super.onDestroy()
    }

    // ---------- notification ----------

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
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )
        return builder
            .setContentTitle("MUHGOUB")
            .setContentText("لوحة التحكم تعمل")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ---------- overlay window ----------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun mm(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics
    ).toInt()

    private fun showOverlay() {
        val themed = ContextThemeWrapper(this, R.style.Theme_Panel)
        val inflater = LayoutInflater.from(themed)
        val view = inflater.inflate(R.layout.overlay_menu, null)

        val w = mm(60f).coerceAtMost(resources.displayMetrics.widthPixels) // 6 cm
        val h = mm(70f) // 7 cm
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

        if (Prefs.getBool(this, "hide_capture")) {
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

        // الترتيب مهم: القايمة الأول ثم الأيقونة، فالأيقونة تفضل فوق القايمة دايماً
        wm.addView(view, params)
        rootView = view
        panelShown = true
        bubble?.let { wm.addView(it, bubbleParams) }
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun applySecureFlag() {
        val hide = Prefs.getBool(this, "hide_capture")
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

    // ---------- floating bubble (always on top of the panel) ----------

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
            // تبدأ في الركن العلوي الأيمن فوق القايمة
            x = dm.widthPixels - size - dp(6)
            y = dp(60)
        }

        if (Prefs.getBool(this, "hide_capture")) {
            bubbleParams.flags = bubbleParams.flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        val b = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            setBackgroundResource(R.drawable.bg_bubble)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = getString(R.string.app_name)
            isClickable = true
            setOnClickListener { togglePanel() }
        }
        setupBubbleTouch(b)
        bubble = b
    }

    private fun togglePanel() {
        if (panelShown) hidePanel() else showPanel()
    }

    private fun showPanel() {
        val panel = rootView ?: return
        if (panelShown) return
        panelShown = true

        // القايمة ترجع تستقبل اللمس، وتظهر بحركة fade + تكبير خفيف
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(panel, params) } catch (e: Exception) { }

        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hidePanel() {
        val panel = rootView ?: return
        if (!panelShown) return
        panelShown = false

        panel.animate().cancel()
        panel.animate()
            .alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!panelShown && rootView != null) {
                    panel.visibility = View.GONE
                    // نافذة شفافة لا تستقبل اللمس، فمتحجبش اللي تحتها
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    try { wm.updateViewLayout(panel, params) } catch (e: Exception) { }
                }
            }
            .start()
    }

    private fun setupBubbleTouch(b: View) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        b.setOnTouchListener { v, e ->
            val dm = resources.displayMetrics
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                    if (moved) {
                        // تفضل جوه حدود الشاشة أثناء السحب
                        bubbleParams.x = (startX + dx).toInt()
                            .coerceIn(0, (dm.widthPixels - bubbleParams.width).coerceAtLeast(0))
                        bubbleParams.y = (startY + dy).toInt()
                            .coerceIn(0, (dm.heightPixels - bubbleParams.height).coerceAtLeast(0))
                        try { wm.updateViewLayout(v, bubbleParams) } catch (ex: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .setInterpolator(OvershootInterpolator()).start()
                    if (moved) snapToEdge(v) else v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    true
                }
                else -> false
            }
        }
    }

    // بعد ما تسيب الأيقونة تنزلق بنعومة لأقرب حافة
    private fun snapToEdge(v: View) {
        val dm = resources.displayMetrics
        val margin = dp(6)
        val left = margin
        val right = (dm.widthPixels - bubbleParams.width - margin).coerceAtLeast(left)
        val target = if (bubbleParams.x + bubbleParams.width / 2 < dm.widthPixels / 2) left else right

        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(bubbleParams.x, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                try { wm.updateViewLayout(v, bubbleParams) } catch (ex: Exception) { }
            }
            start()
        }
    }

    private fun setupDrag(header: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    rootView?.let { wm.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    // ---------- content ----------

    private val checkLabels = listOf(
        "حبل 1", "حبل٢",
        "كين٣", "عمو٤",
        "جين٥", "كين٦",
        "تفعيل رقم ٧", "تفعيل رقم ٨",
        "تفعيل رقم ٩", "تفعيل رقم ١٠",
        "تفعيل رقم ١١", "تفعيل رقم ١٢"
    )

    private fun buildChecks(ctx: Context, inflater: LayoutInflater, container: LinearLayout) {
        for (r in 0 until 6) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (c in 0..1) {
                val idx = r * 2 + c
                val item = inflater.inflate(R.layout.item_check, row, false)
                item.layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                bindCheck(item, checkLabels[idx], "chk_$idx")
                row.addView(item)
            }
            container.addView(row)
        }
    }

    private fun bindCheck(item: View, label: String, key: String) {
        val text = item.findViewById<TextView>(R.id.item_text)
        val box = item.findViewById<CheckBox>(R.id.item_box)
        text.text = label

        fun render() {
            val on = Prefs.getBool(this, key)
            box.isChecked = on
            item.setBackgroundResource(if (on) R.drawable.bg_item_on else R.drawable.bg_item)
        }
        render()
        item.setOnClickListener {
            val newState = !Prefs.getBool(this, key)
            Prefs.setBool(this, key, newState)
            render()
            toast(newState)
        }
    }

    private fun buildGroup(ctx: Context, container: LinearLayout, key: String, labels: List<String>) {
        val offIndex = labels.lastIndex
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val pills = labels.mapIndexed { i, label ->
            TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 16f
                maxLines = 1
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
        }

        fun render() {
            val sel = Prefs.getInt(this, key, offIndex)
            pills.forEachIndexed { i, pill ->
                val active = i == sel && i != offIndex
                pill.setBackgroundResource(if (active) R.drawable.bg_pill_on else R.drawable.bg_pill)
                pill.setTextColor(if (i == offIndex && !active) 0xFFBDBDBD.toInt() else 0xFFFFFFFF.toInt())
            }
        }

        pills.forEachIndexed { i, pill ->
            pill.setOnClickListener {
                val old = Prefs.getInt(this, key, offIndex)
                val newSel = if (i == old) offIndex else i
                Prefs.setInt(this, key, newSel)
                render()
                toast(newSel != offIndex)
            }
            row.addView(pill)
        }
        render()
        container.addView(row)
    }

    private fun addSpace(ctx: Context, container: LinearLayout, heightDp: Int) {
        container.addView(View(ctx), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun toast(on: Boolean) {
        Toast.makeText(
            this,
            getString(if (on) R.string.toast_on else R.string.toast_off),
            Toast.LENGTH_SHORT
        ).show()
    }
}
