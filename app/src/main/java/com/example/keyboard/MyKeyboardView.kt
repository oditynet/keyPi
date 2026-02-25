package com.example.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.AttributeSet
import android.view.MotionEvent

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
    var onKeyWithPositionListener: ((Int, MyKeyboardView.TouchPosition) -> Unit)? = null

    // Добавить enum внутри класса MyKeyboardView (после existing переменных)
    enum class TouchPosition {
        TOP_LEFT, TOP, TOP_RIGHT,
        LEFT, CENTER, RIGHT,
        BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT
    }

    // Добавить метод getKeyAt (после существующих методов)
    private fun getKeyAt(x: Float, y: Float): Keyboard.Key? {
        val keys = keyboard?.keys ?: return null
        for (key in keys) {
            if (x >= key.x && x <= key.x + key.width &&
                y >= key.y && y <= key.y + key.height) {
                return key
            }
        }
        return null
    }

    // Добавить метод getTouchPositionOnKey (после getKeyAt)
    private fun getTouchPositionOnKey(key: Keyboard.Key, x: Float, y: Float): TouchPosition {
        val relX = (x - key.x) / key.width
        val relY = (y - key.y) / key.height

        return when {
            relY < 0.25 && relX < 0.25 -> TouchPosition.TOP_LEFT
            relY < 0.25 && relX > 0.75 -> TouchPosition.TOP_RIGHT
            relY < 0.25 -> TouchPosition.TOP

            relY > 0.75 && relX < 0.25 -> TouchPosition.BOTTOM_LEFT
            relY > 0.75 && relX > 0.75 -> TouchPosition.BOTTOM_RIGHT
            relY > 0.75 -> TouchPosition.BOTTOM

            relX < 0.25 -> TouchPosition.LEFT
            relX > 0.75 -> TouchPosition.RIGHT
            else -> TouchPosition.CENTER
        }
    }

    // Переопределить onTouchEvent (добавить после существующих методов)
    override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            val x = event.x
            val y = event.y
            val key = getKeyAt(x, y)

            if (key != null && key.codes.isNotEmpty()) {
                val touchPosition = getTouchPositionOnKey(key, x, y)
                onKeyWithPositionListener?.invoke(key.codes[0], touchPosition)
                return true // Говорим, что обработали событие
            }
        }
    }
    return super.onTouchEvent(event) // Это будет вызвано только если мы не вернули true
}
}

