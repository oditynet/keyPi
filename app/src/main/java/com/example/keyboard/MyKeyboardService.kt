package com.example.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.content.ClipboardManager
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.view.Gravity
import android.widget.FrameLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MyKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private var keyboardView: MyKeyboardView? = null
    private var currentKeyboard: Keyboard? = null
    private var currentLanguage = "ru"
    private var currentMode = "letters"

    // Переменные для разных раскладок
    private var mRussianKeyboardWithoutNumbers: Keyboard? = null
    private var mRussianKeyboardWithNumbers: Keyboard? = null
    private var mEnglishKeyboardWithoutNumbers: Keyboard? = null
    private var mEnglishKeyboardWithNumbers: Keyboard? = null
    private var mEmojiKeyboard: Keyboard? = null
    private var mSymbolKeyboard: Keyboard? = null

    // Состояние Shift
    private var shiftState = ShiftState.OFF

    private lateinit var prefs: SharedPreferences
    private lateinit var vibrator: Vibrator

    // Для долгих нажатий
    private var isKeyPressed = false
    private var longPressHandled = false
    private var pressedKeyCode = 0
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private val LONG_PRESS_TIME = 400L

    // Настройки
    @Volatile
    private var touchSensitivity = 70
    @Volatile
    private var useContext = true
    @Volatile
    private var vibroEnabled = true
    @Volatile
    private var keySize = 1 // 0 - маленький, 1 - средний, 2 - большой

    // НОВОЕ: Для буфера обмена
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager
    private var clipboardButton: Button? = null
    private var clipboardPopup: PopupWindow? = null
    private var rootContainer: LinearLayout? = null  // ИЗМЕНЕНО: LinearLayout вместо FrameLayout

    private val density by lazy { resources.displayMetrics.density }

    enum class ShiftState {
        OFF, ON, TEMPORARY
    }

    companion object {
        const val KEYCODE_LANG_SWITCH = -2
        const val KEYCODE_NUMBERS = -3
        const val KEYCODE_EMOJI = -4
        const val KEYCODE_SYMBOLS = -6
        const val KEYCODE_BACK_TO_LETTERS = -7

        const val PREF_KEY_LANGUAGE = "keyboard_language"
        const val PREF_KEY_TOUCH_SENSITIVITY = "touch_sensitivity"
        const val PREF_KEY_USE_CONTEXT = "use_context"
        const val PREF_KEY_VIBRO = "vibro"
        const val PREF_KEY_KEY_SIZE = "key_size"

        private const val TAG = "keyPi"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // НОВОЕ: Инициализация буфера обмена
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardHistoryManager = ClipboardHistoryManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            clipboardManager.addPrimaryClipChangedListener {
                checkClipboardForCopy()
            }
        }

        loadSettings()

        mRussianKeyboardWithoutNumbers = Keyboard(this, R.xml.keyboard_layout_ru)
        mRussianKeyboardWithNumbers = Keyboard(this, R.xml.keyboard_layout_ru_number)
        mEnglishKeyboardWithoutNumbers = Keyboard(this, R.xml.keyboard_layout_en)
        mEnglishKeyboardWithNumbers = Keyboard(this, R.xml.keyboard_layout_en_number)
        mEmojiKeyboard = Keyboard(this, R.xml.keyboard_layout_emoji)
        mSymbolKeyboard = Keyboard(this, R.xml.keyboard_layout_symbols)

        currentLanguage = prefs.getString(PREF_KEY_LANGUAGE, "ru") ?: "ru"
        Log.d(TAG, "Keyboard service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PREF_KEY_TOUCH_SENSITIVITY -> {
                touchSensitivity = prefs.getInt(PREF_KEY_TOUCH_SENSITIVITY, 70)
                Log.d(TAG, "Sensitivity updated to $touchSensitivity")
            }
            PREF_KEY_USE_CONTEXT -> {
                useContext = prefs.getBoolean(PREF_KEY_USE_CONTEXT, true)
                Log.d(TAG, "Use context updated to $useContext")
            }
            PREF_KEY_VIBRO -> {
                vibroEnabled = prefs.getBoolean(PREF_KEY_VIBRO, false)
                Log.d(TAG, "Vibro updated to $vibroEnabled")
            }
            PREF_KEY_KEY_SIZE -> {
                keySize = prefs.getInt(PREF_KEY_KEY_SIZE, 1)
                Log.d(TAG, "Key size updated to $keySize")
                applyKeySize()
            }
            PREF_KEY_LANGUAGE -> {
                currentLanguage = prefs.getString(PREF_KEY_LANGUAGE, "ru") ?: "ru"
                Log.d(TAG, "Language updated to $currentLanguage")
                loadKeyboard(currentLanguage, currentMode)
            }
        }
    }

    private fun loadSettings() {
        touchSensitivity = prefs.getInt(PREF_KEY_TOUCH_SENSITIVITY, 70)
        useContext = prefs.getBoolean(PREF_KEY_USE_CONTEXT, true)
        vibroEnabled = prefs.getBoolean(PREF_KEY_VIBRO, true)
        keySize = prefs.getInt(PREF_KEY_KEY_SIZE, 1)

        Log.d(TAG, "Settings loaded: sensitivity=$touchSensitivity, useContext=$useContext, vibro=$vibroEnabled, keySize=$keySize")
    }

    // НОВОЕ: Проверка буфера обмена
    private fun checkClipboardForCopy() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: item.coerceToText(this)?.toString() ?: return

            clipboardHistoryManager.addToHistory(text)
            // Показываем кнопку
            clipboardButton?.visibility = View.VISIBLE
        }
    }

    // НОВОЕ: Создание кнопки буфера (маленькая, 50x50 dp)
    private fun createClipboardButton(): Button {
        return Button(this).apply {
            text = "📋"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2196F3"))
            textSize = 16f
            elevation = 10f
            alpha = 0.9f

            // Размер 50x50 dp в пикселях
            val sizeInPx = (50 * density).toInt()

            layoutParams = FrameLayout.LayoutParams(sizeInPx, sizeInPx).apply {
                gravity = Gravity.END  // прижимаем к правому краю
                rightMargin = (20 * density).toInt()
            }

            setOnClickListener {
                showClipboardHistory()
            }
        }
    }

    // НОВОЕ: Показать историю
    // НОВОЕ: Показать историю (КОМПАКТНАЯ ВЕРСИЯ)
    private fun showClipboardHistory() {
        val view = keyboardView ?: return
        val history = clipboardHistoryManager.getHistory()

        if (history.isEmpty()) {
            return
        }

        // СОЗДАЕМ ВЕРТИКАЛЬНЫЙ LINEARLAYOUT ДЛЯ POPUP
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD333333"))
            setPadding(8, 8, 8, 8)  // УМЕНЬШИЛИ PADDING с 20 до 10
            elevation = 20f
        }

        // 2. ЭЛЕМЕНТЫ ИСТОРИИ (более компактные)
        for (text in history) {
            val previewText = if (text.length > 15) text.substring(0, 12) + "..." else text  // ПОКАЗЫВАЕМ БОЛЬШЕ ТЕКСТА
            val fullText = text

            val itemView = TextView(this).apply {
                this.text = previewText  // УБРАЛИ ПОКАЗ ДЛИНЫ, ТОЛЬКО ТЕКСТ
                setTextColor(Color.WHITE)
                textSize = 13f  // УМЕНЬШИЛИ РАЗМЕР с 14 до 13
                setPadding(15, 12, 15, 12)  // УМЕНЬШИЛИ PADDING
                setBackgroundColor(Color.parseColor("#666666"))

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 2, 0, 2)  // УМЕНЬШИЛИ MARGIN с 5 до 2
                }

                setOnClickListener {
                    currentInputConnection?.commitText(fullText, 1)
                    clipboardPopup?.dismiss()
                }
            }
            popupView.addView(itemView)
        }

        // 3. КНОПКИ В ОДНУ СТРОКУ (горизонтально)
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Кнопка очистки
        val clearButton = TextView(this).apply {
            text = "Очистить"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.parseColor("#AA5555"))
            gravity = Gravity.CENTER

            layoutParams = LinearLayout.LayoutParams(
                0,  // вес
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f  // вес 1 - занимает половину
            ).apply {
                rightMargin = 2
            }

            setOnClickListener {
                clipboardHistoryManager.clear()
                clipboardButton?.visibility = View.GONE
                clipboardPopup?.dismiss()
            }
        }

        // Кнопка закрытия
        val closeButton = TextView(this).apply {
            text = "Закрыть"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.parseColor("#777777"))
            gravity = Gravity.CENTER

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f  // вес 1 - занимает половину
            ).apply {
                leftMargin = 2
            }

            setOnClickListener {
                clipboardPopup?.dismiss()
            }
        }

        buttonRow.addView(clearButton)
        buttonRow.addView(closeButton)
        popupView.addView(buttonRow)

        // СОЗДАЕМ POPUPWINDOW
        clipboardPopup = PopupWindow(
            popupView,
            (view.width * 0.7).toInt(),  // УМЕНЬШИЛИ ШИРИНУ с 80% до 70%
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )

        // ПОКАЗЫВАЕМ POPUP
        clipboardPopup?.showAtLocation(view, Gravity.TOP, 0, 150)  // УМЕНЬШИЛИ ОТСТУП СВЕРХУ
    }

    override fun onCreateInputView(): View {
        // Создаем вертикальный LinearLayout как корневой контейнер
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Создаем контейнер для кнопки (отдельная строка)
        val buttonContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Создаем кнопку буфера
        clipboardButton = createClipboardButton()
        buttonContainer.addView(clipboardButton)

        // Создаем клавиатуру
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as MyKeyboardView
        keyboardView?.setOnKeyboardActionListener(this)
        keyboardView?.isPreviewEnabled = false

        // Добавляем в корневой контейнер: сначала кнопку, потом клавиатуру
        rootContainer?.addView(buttonContainer)
        rootContainer?.addView(keyboardView)

        applyKeySize()
        loadKeyboard(currentLanguage, currentMode)

        return rootContainer!!
    }

    private fun applyKeySize() {
        val view = keyboardView ?: return

        val keyboardHeight = when (keySize) {
            0 -> 180  // маленькая клавиатура
            1 -> 220  // средняя клавиатура
            2 -> 260  // большая клавиатура
            else -> 220
        }.dpToPx()

        val params = view.layoutParams ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            keyboardHeight
        )
        params.height = keyboardHeight

        view.layoutParams = params
        view.requestLayout()

        Log.d(TAG, "Keyboard height set to: $keyboardHeight px (keySize=$keySize)")
    }

    private fun Int.dpToPx(): Int = (this * density).toInt()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "Start input view")

        loadSettings()
        applyKeySize()

        if (!restarting) {
            currentMode = "letters"
            shiftState = ShiftState.OFF
            loadKeyboard(currentLanguage, currentMode)
        }

        moveKeyboardAboveNavBar()
        updateShiftIndicator()

        // Кнопка всегда видна сверху, если есть история
        clipboardButton?.visibility = if (clipboardHistoryManager.getHistory().isNotEmpty())
            View.VISIBLE else View.GONE
    }

    override fun onWindowShown() {
        super.onWindowShown()
        moveKeyboardAboveNavBar()
    }

    private fun moveKeyboardAboveNavBar() {
        val view = keyboardView ?: return
        val window = window?.window ?: return

        val navBarHeight = getNavigationBarHeight()
        Log.d(TAG, "Navigation bar height: $navBarHeight px")

        view.setPadding(0, 0, 0, navBarHeight)
        view.requestLayout()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            (48 * density).toInt()
        }
    }

    private fun loadKeyboard(language: String, mode: String) {
        when (mode) {
            "symbols" -> {
                currentKeyboard = mSymbolKeyboard
                Log.d(TAG, "Loading symbols keyboard")
            }
            "emoji" -> {
                currentKeyboard = mEmojiKeyboard
                Log.d(TAG, "Loading emoji keyboard")
            }
            "numbers" -> {
                currentKeyboard = if (language == "ru")
                    mRussianKeyboardWithNumbers
                else
                    mEnglishKeyboardWithNumbers
                Log.d(TAG, "Loading numbers keyboard for $language")
            }
            else -> { // "letters"
                currentKeyboard = if (language == "ru")
                    mRussianKeyboardWithoutNumbers
                else
                    mEnglishKeyboardWithoutNumbers
                Log.d(TAG, "Loading letters keyboard for $language")
            }
        }

        keyboardView?.keyboard = currentKeyboard
        keyboardView?.invalidateAllKeys()
    }

    private fun updateShiftIndicator() {
        when (shiftState) {
            ShiftState.ON, ShiftState.TEMPORARY -> {
                currentKeyboard?.setShifted(true)
                keyboardView?.invalidateAllKeys()
            }
            ShiftState.OFF -> {
                currentKeyboard?.setShifted(false)
                keyboardView?.invalidateAllKeys()
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        isKeyPressed = true
        longPressHandled = false
        pressedKeyCode = primaryCode

        vibroEnabled = prefs.getBoolean(PREF_KEY_VIBRO, true)

        val adjustedLongPressTime = (LONG_PRESS_TIME * (100 - touchSensitivity) / 50).toLong().coerceIn(200L, 800L)

        longPressRunnable = Runnable {
            if (isKeyPressed && !longPressHandled) {
                longPressHandled = true
                handleLongPress(pressedKeyCode)
            }
        }
        longPressHandler.postDelayed(longPressRunnable!!, adjustedLongPressTime)

        if (vibroEnabled) {
            vibrate()
        }
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Vibrate permission denied")
        }
    }

    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(10)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Vibrate permission denied")
        }
    }

    override fun onRelease(primaryCode: Int) {
        longPressHandler.removeCallbacks(longPressRunnable!!)
        isKeyPressed = false
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        if (longPressHandled) {
            if (shiftState == ShiftState.TEMPORARY) {
                shiftState = ShiftState.OFF
                updateShiftIndicator()
            }
            return
        }
        processNormalKey(primaryCode)
    }

    private fun processNormalKey(keyCode: Int) {
        val inputConnection = currentInputConnection ?: return

        when (keyCode) {
            Keyboard.KEYCODE_DELETE -> {
                inputConnection.deleteSurroundingText(1, 0)
                if (shiftState == ShiftState.TEMPORARY) {
                    shiftState = ShiftState.OFF
                    updateShiftIndicator()
                }
            }

            Keyboard.KEYCODE_SHIFT -> {
                shiftState = when (shiftState) {
                    ShiftState.OFF -> ShiftState.TEMPORARY
                    ShiftState.TEMPORARY -> ShiftState.ON
                    ShiftState.ON -> ShiftState.OFF
                }
                updateShiftIndicator()
            }

            10 -> {
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                if (shiftState == ShiftState.TEMPORARY) {
                    shiftState = ShiftState.OFF
                    updateShiftIndicator()
                }
            }

            32 -> {
                inputConnection.commitText(" ", 1)
                if (shiftState == ShiftState.TEMPORARY) {
                    shiftState = ShiftState.OFF
                    updateShiftIndicator()
                }
            }

            KEYCODE_LANG_SWITCH -> {
                currentLanguage = if (currentLanguage == "ru") "en" else "ru"
                currentMode = "letters"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
                prefs.edit().putString(PREF_KEY_LANGUAGE, currentLanguage).apply()
            }

            KEYCODE_NUMBERS -> {
                currentMode = if (currentMode == "letters") "numbers" else "letters"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
            }

            KEYCODE_EMOJI -> {
                currentMode = "emoji"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
            }

            KEYCODE_SYMBOLS -> {
                currentMode = "symbols"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
            }

            KEYCODE_BACK_TO_LETTERS -> {
                currentMode = "letters"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
            }

            else -> {
                if (keyCode in 0x1F600..0x1F64F) {
                    val emoji = String(Character.toChars(keyCode))
                    inputConnection.commitText(emoji, 1)
                } else {
                    val char = keyCode.toChar()
                    val shouldBeUpper = shiftState == ShiftState.ON || shiftState == ShiftState.TEMPORARY
                    val textToCommit = if (shouldBeUpper && char.isLetter()) {
                        char.uppercaseChar().toString()
                    } else {
                        char.toString()
                    }
                    inputConnection.commitText(textToCommit, 1)

                    if (shiftState == ShiftState.TEMPORARY) {
                        shiftState = ShiftState.OFF
                        updateShiftIndicator()
                    }
                }
            }
        }
    }

    private fun handleLongPress(keyCode: Int) {
        val inputConnection = currentInputConnection ?: return
        val shouldBeUpper = shiftState == ShiftState.ON || shiftState == ShiftState.TEMPORARY

        if (vibroEnabled) {
            vibrateShort()
        }

        when (keyCode) {
            1073 -> inputConnection.commitText(if (shouldBeUpper) "Ю" else "ю", 1)
            1077 -> inputConnection.commitText(if (shouldBeUpper) "Ё" else "ё", 1)
            1080 -> inputConnection.commitText(if (shouldBeUpper) "Й" else "й", 1)
            1100 -> inputConnection.commitText(if (shouldBeUpper) "Ъ" else "ъ", 1)
            1078 -> inputConnection.commitText(if (shouldBeUpper) "Э" else "э", 1)
            1079 -> inputConnection.commitText(if (shouldBeUpper) "Х" else "х", 1)
            44 -> inputConnection.commitText(".", 1)
            46 -> inputConnection.commitText(",", 1)
            KEYCODE_SYMBOLS -> {
                currentMode = "emoji"
                loadKeyboard(currentLanguage, currentMode)
            }
            101 -> inputConnection.commitText(if (shouldBeUpper) "Ё" else "ё", 1)
        }

        if (shiftState == ShiftState.TEMPORARY) {
            shiftState = ShiftState.OFF
            updateShiftIndicator()
        }
    }

    override fun onText(text: CharSequence?) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}