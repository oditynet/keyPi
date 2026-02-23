package com.example.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet

class MyKeyboardView : KeyboardView {

    private val secondaryPaint: Paint = Paint()

    // Словарь соответствий: основной символ -> второстепенный
    private val secondaryMap = mapOf(
        // Русские
        "е" to "ё",
        "и" to "й",
        "ь" to "ъ",
        "з" to "х",
        "ж" to "э",
        "б" to "ю",
        // Английские (без ё)
        "," to ".",
        "." to ","
    )

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        secondaryPaint.color = Color.argb(128, 0, 0, 0) // 50% прозрачности
        secondaryPaint.textAlign = Paint.Align.CENTER
        secondaryPaint.typeface = Typeface.DEFAULT
    }

    override fun onDraw(canvas: Canvas) {
        // Сначала рисуем стандартные клавиши
        super.onDraw(canvas)

        val keyboard = keyboard ?: return

        for (key in keyboard.keys) {
            if (key.label == null) continue

            val mainChar = key.label.toString()

            // Ищем второстепенный символ по словарю
            val secondaryChar = secondaryMap[mainChar]

            if (secondaryChar != null) {
                // Рисуем второстепенный символ
                secondaryPaint.textSize = key.height * 0.2f
                val secondaryX = key.x + key.width * 0.8f
                val secondaryY = key.y + key.height * 0.8f
                canvas.drawText(secondaryChar, secondaryX, secondaryY, secondaryPaint)
            }
        }
    }
}

