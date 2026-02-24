package com.example.keyboard

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ClipboardHistoryManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val maxSize = 5

    private var clipboardHistory: MutableList<String> = mutableListOf()

    init {
        loadHistory()
    }

    fun addToHistory(text: String) {
        if (text.isBlank()) return

        clipboardHistory.remove(text)
        clipboardHistory.add(0, text)

        while (clipboardHistory.size > maxSize) {
            clipboardHistory.removeAt(clipboardHistory.size - 1)
        }

        saveHistory()
    }

    fun getHistory(): List<String> = clipboardHistory.toList()

    fun clear() {
        clipboardHistory.clear()
        saveHistory()
    }

    private fun loadHistory() {
        val json = prefs.getString("history", null)
        if (json != null) {
            val type = object : TypeToken<MutableList<String>>() {}.type
            clipboardHistory = gson.fromJson(json, type) ?: mutableListOf()
        }
    }

    private fun saveHistory() {
        val json = gson.toJson(clipboardHistory)
        prefs.edit().putString("history", json).apply()
    }
}