package com.muhgoub.panel

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "panel_prefs"

    fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getBool(c: Context, key: String, def: Boolean = false): Boolean =
        sp(c).getBoolean(key, def)

    fun setBool(c: Context, key: String, value: Boolean) {
        sp(c).edit().putBoolean(key, value).apply()
    }

    fun getInt(c: Context, key: String, def: Int): Int = sp(c).getInt(key, def)

    fun setInt(c: Context, key: String, value: Int) {
        sp(c).edit().putInt(key, value).apply()
    }
}
