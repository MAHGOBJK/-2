package com.muhgoub.panel

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val keys = listOf("dm", "ap", "fl", "bt", "wifi")
    private val circleIds = listOf(
        R.id.circle_dm, R.id.circle_ap, R.id.circle_fl, R.id.circle_bt, R.id.circle_wifi
    )

    private lateinit var btnNormal: TextView
    private lateinit var btnSuper: TextView
    private lateinit var switchHide: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnNormal = findViewById(R.id.btn_normal)
        btnSuper = findViewById(R.id.btn_super)
        switchHide = findViewById(R.id.switch_hide)

        circleIds.forEachIndexed { i, id ->
            findViewById<FrameLayout>(id).setOnClickListener { onCircleClick(keys[i]) }
        }

        findViewById<TextView>(R.id.btn_start).setOnClickListener { startPanel() }
        findViewById<TextView>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            toastState(getString(R.string.stop_menu_name), false)
        }

        btnNormal.setOnClickListener {
            setMode(false)
            toastState(getString(R.string.mode_normal), true)
        }
        btnSuper.setOnClickListener {
            setMode(true)
            toastState(getString(R.string.mode_super), true)
        }

        switchHide.isChecked = Prefs.getBool(this, "hide_capture")
        switchHide.setOnCheckedChangeListener { button, checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                button.isChecked = false
                toast(getString(R.string.need_overlay))
                requestOverlayPermission()
            } else {
                val changed = Prefs.getBool(this, "hide_capture") != checked
                Prefs.setBool(this, "hide_capture", checked)
                if (changed) toastState(getString(R.string.hide_title), checked)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncSystemStates()
        refreshCircles()
        refreshMode()
    }

    // ---------- circles ----------

    private fun refreshCircles() {
        circleIds.forEachIndexed { i, id ->
            val frame = findViewById<FrameLayout>(id)
            val on = Prefs.getBool(this, keys[i])
            frame.setBackgroundResource(if (on) R.drawable.bg_circle_on else R.drawable.bg_circle_off)
            // الأيقونة غامقة على الدايرة السماوي (شغال) ورمادي فاتح وهي مطفية
            (frame.getChildAt(0) as ImageView).setColorFilter(
                if (on) 0xFF0A0A0A.toInt() else 0xFFBDBDBD.toInt()
            )
        }
    }

    private fun onCircleClick(key: String) {
        val newState = !Prefs.getBool(this, key)
        when (key) {
            "fl" -> if (!setTorch(newState)) return
            "bt" -> if (!setBluetooth(newState)) return
            "wifi" -> openWifiSettings()
            "ap" -> startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            "dm" -> startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        Prefs.setBool(this, key, newState)
        refreshCircles()
        toastState(circleLabel(key), newState)
    }

    // اسم الزرار = النص الظاهر تحت الدايرة في الواجهة
    private fun circleLabel(key: String): String {
        val frame = findViewById<FrameLayout>(circleIds[keys.indexOf(key)])
        return ((frame.parent as LinearLayout).getChildAt(1) as TextView).text.toString()
    }

    private fun setTorch(on: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                toast("No flash")
                false
            } else {
                cm.setTorchMode(id, on)
                true
            }
        } catch (e: Exception) {
            toast("Flash error")
            false
        }
    }

    private fun setBluetooth(on: Boolean): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            toast("No Bluetooth")
            return false
        }
        return try {
            if (on) adapter.enable() else adapter.disable()
            true
        } catch (e: SecurityException) {
            toast("Bluetooth permission denied")
            false
        }
    }

    private fun openWifiSettings() {
        if (Build.VERSION.SDK_INT >= 29) {
            startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        } else {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun syncSystemStates() {
        try {
            val bt = BluetoothAdapter.getDefaultAdapter()
            if (bt != null) Prefs.setBool(this, "bt", bt.isEnabled)
        } catch (e: SecurityException) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            Prefs.setBool(this, "wifi", wm.isWifiEnabled)
        } catch (e: SecurityException) {
        }
    }

    // ---------- mode buttons ----------

    private fun setMode(superMode: Boolean) {
        Prefs.setBool(this, "mode_super", superMode)
        refreshMode()
    }

    private fun refreshMode() {
        val superMode = Prefs.getBool(this, "mode_super")
        btnSuper.setBackgroundResource(if (superMode) R.drawable.bg_mode_on else R.drawable.bg_mode_off)
        btnNormal.setBackgroundResource(if (superMode) R.drawable.bg_mode_off else R.drawable.bg_mode_on)
    }

    // ---------- overlay ----------

    private fun startPanel() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.need_overlay))
            requestOverlayPermission()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        toastState(getString(R.string.start_panel_name), true)
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    // "تم تشغيل <الاسم>" أو "تم إيقاف <الاسم>"
    private fun toastState(name: String, on: Boolean) =
        toast(getString(if (on) R.string.toast_started else R.string.toast_stopped, name))

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
