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
import android.preference.PreferenceManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class MyKeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private var keyboardView: MyKeyboardView? = null
    private var currentKeyboard: Keyboard? = null
    private var currentLanguage = "ru"
    private var currentMode = "letters"

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

    // Настройки - делаем их volatile для безопасного доступа из разных потоков
    @Volatile
    private var touchSensitivity = 70
    @Volatile
    private var useContext = true
    @Volatile
    private var vibroEnabled = true
    @Volatile
    private var keySize = 1 // 0 - маленький, 1 - средний, 2 - большой

    private val density by lazy { resources.displayMetrics.density }

    enum class ShiftState {
        OFF,        // все маленькие
        ON,         // все большие (Caps Lock)
        TEMPORARY   // следующая буква большая
    }

    companion object {
        const val KEYCODE_LANG_SWITCH = -2
        const val KEYCODE_EMOJI = -3
        const val KEYCODE_SYMBOLS = -4
        const val KEYCODE_BACK_TO_LETTERS = -6

        const val PREF_KEY_LANGUAGE = "keyboard_language"
        const val PREF_KEY_TOUCH_SENSITIVITY = "touch_sensitivity"
        const val PREF_KEY_USE_CONTEXT = "use_context"
        const val PREF_KEY_VIBRO = "vibro"
        const val PREF_KEY_KEY_SIZE = "key_size"

        private const val TAG = "MyKeyboard"
    }

    override fun onCreate() {
        super.onCreate()
        // ВАЖНО: используем ТОЧНО такое же имя файла
        prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)

        // Регистрируем слушатель
        prefs.registerOnSharedPreferenceChangeListener(this)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Загружаем настройки
        loadSettings()

        currentLanguage = prefs.getString(PREF_KEY_LANGUAGE, "ru") ?: "ru"
        Log.d(TAG, "Keyboard service created")
    }


    override fun onDestroy() {
        super.onDestroy()
        // Отписываемся от изменений настроек
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    // Слушатель изменений настроек
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
                vibroEnabled = prefs.getBoolean(PREF_KEY_VIBRO, true)
                Log.d(TAG, "Vibro updated to $vibroEnabled")
            }
            PREF_KEY_KEY_SIZE -> {
                keySize = prefs.getInt(PREF_KEY_KEY_SIZE, 1)
                Log.d(TAG, "Key size updated to $keySize")
                // Применяем новый размер клавиш
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

    override fun onCreateInputView(): View {
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null) as MyKeyboardView
        keyboardView?.setOnKeyboardActionListener(this)

        keyboardView?.isPreviewEnabled = false

        // Применяем размер клавиш
        applyKeySize()

        loadKeyboard(currentLanguage, currentMode)

        return keyboardView!!
    }

    private fun applyKeySize() {
        val view = keyboardView ?: return

        val params = view.layoutParams ?: ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        params.height = when (keySize) {
            0 -> 180.dpToPx()  // маленький
            1 -> 220.dpToPx()  // средний
            2 -> 260.dpToPx()  // большой
            else -> 220.dpToPx()
        }


        view.layoutParams = params
        view.requestLayout()
    }

    private fun Int.dpToPx(): Int {
        return (this * density).toInt()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "Start input view")

        // Перезагружаем настройки при каждом открытии
        loadSettings()

        // Применяем размер клавиш
        applyKeySize()

        if (!restarting) {
            currentMode = "letters"
            shiftState = ShiftState.OFF
            loadKeyboard(currentLanguage, currentMode)
        }


        moveKeyboardAboveNavBar()

        updateShiftIndicator()
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
        currentKeyboard = when (mode) {
            "symbols" -> Keyboard(this, R.xml.keyboard_layout_symbols)
            "emoji" -> Keyboard(this, R.xml.keyboard_layout_emoji)
            else -> {
                when (language) {
                    "ru" -> Keyboard(this, R.xml.keyboard_layout_ru)
                    else -> Keyboard(this, R.xml.keyboard_layout_en)
                }
            }
        }
        keyboardView?.keyboard = currentKeyboard
        keyboardView?.invalidateAllKeys()
        Log.d(TAG, "Keyboard loaded: language=$language, mode=$mode")
    }

    private fun updateShiftIndicator() {
        when (shiftState) {
            ShiftState.ON -> {
                currentKeyboard?.setShifted(true)
                keyboardView?.invalidateAllKeys()
            }
            ShiftState.OFF -> {
                currentKeyboard?.setShifted(false)
                keyboardView?.invalidateAllKeys()
            }
            ShiftState.TEMPORARY -> {
                currentKeyboard?.setShifted(true)
                keyboardView?.invalidateAllKeys()
            }
        }
    }

    override fun onPress(primaryCode: Int) {
        isKeyPressed = true
        longPressHandled = false
        pressedKeyCode = primaryCode


        // КАЖДЫЙ РАЗ ЧИТАЕМ НАСТРОЙКУ ЗАНОВО (для надежности)
        vibroEnabled = prefs.getBoolean(PREF_KEY_VIBRO, true)

        Log.d(TAG, "onPress: $primaryCode, shiftState=$shiftState, vibroEnabled=$vibroEnabled")

        val adjustedLongPressTime = (LONG_PRESS_TIME * (100 - touchSensitivity) / 50).toLong().coerceIn(200L, 800L)

        longPressRunnable = Runnable {
            if (isKeyPressed && !longPressHandled) {
                longPressHandled = true
                handleLongPress(pressedKeyCode)
            }
        }
        longPressHandler.postDelayed(longPressRunnable!!, adjustedLongPressTime)

        // Вибро при нажатии - ТОЛЬКО ЕСЛИ ВКЛЮЧЕНО
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
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed: ${e.message}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed: ${e.message}")
        }
    }

    override fun onRelease(primaryCode: Int) {
        Log.d(TAG, "onRelease: $primaryCode, longPressHandled=$longPressHandled")

        longPressHandler.removeCallbacks(longPressRunnable!!)
        isKeyPressed = false
    }

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        Log.d(TAG, "onKey: $primaryCode, longPressHandled=$longPressHandled")

        if (longPressHandled) {
            Log.d(TAG, "Long press handled - ignoring onKey")

            if (shiftState == ShiftState.TEMPORARY) {
                shiftState = ShiftState.OFF
                updateShiftIndicator()
            }
            return
        }

        processNormalKey(primaryCode)
    }

    private fun processNormalKey(keyCode: Int) {
        val inputConnection = currentInputConnection
        Log.d(TAG, "processNormalKey: $keyCode, shiftState=$shiftState")

        when (keyCode) {
            Keyboard.KEYCODE_DELETE -> { // -5
                inputConnection?.deleteSurroundingText(1, 0) ?: return
                Log.d(TAG, "Delete pressed")

                if (shiftState == ShiftState.TEMPORARY) {
                    shiftState = ShiftState.OFF
                    updateShiftIndicator()
                }
            }

            Keyboard.KEYCODE_SHIFT -> { // -1
                shiftState = when (shiftState) {
                    ShiftState.OFF -> ShiftState.TEMPORARY
                    ShiftState.TEMPORARY -> ShiftState.ON
                    ShiftState.ON -> ShiftState.OFF
                }
                Log.d(TAG, "Shift pressed, new state: $shiftState")
                updateShiftIndicator()
                return
            }

            10 -> { // Enter
                inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                Log.d(TAG, "Enter pressed")

                if (shiftState == ShiftState.TEMPORARY) {
                    shiftState = ShiftState.OFF
                    updateShiftIndicator()
                }
                return
            }

            32 -> { // Space
                inputConnection?.commitText(" ", 1)
                Log.d(TAG, "Space pressed")

                if (shiftState == ShiftState.TEMPORARY) {
                    shiftState = ShiftState.OFF
                    updateShiftIndicator()
                }
                return
            }

            KEYCODE_LANG_SWITCH -> { // -2
                currentLanguage = if (currentLanguage == "ru") "en" else "ru"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
                prefs.edit().putString(PREF_KEY_LANGUAGE, currentLanguage).apply()
                Log.d(TAG, "Language switch to $currentLanguage")
                return
            }

            KEYCODE_EMOJI, KEYCODE_SYMBOLS -> { // -3, -4
                currentMode = if (currentMode == "letters") "symbols" else "emoji"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
                Log.d(TAG, "Mode switch to $currentMode")
                return
            }

            KEYCODE_BACK_TO_LETTERS -> { // -6
                currentMode = "letters"
                loadKeyboard(currentLanguage, currentMode)
                shiftState = ShiftState.OFF
                updateShiftIndicator()
                Log.d(TAG, "Back to letters")
                return
            }

            else -> {
                if (inputConnection == null) {
                    Log.d(TAG, "No input connection")
                    return
                }

                if (keyCode in 0x1F600..0x1F64F) {
                    val emoji = String(Character.toChars(keyCode))
                    inputConnection.commitText(emoji, 1)
                    Log.d(TAG, "Emoji: $emoji")
                } else {
                    val char = keyCode.toChar()

                    val shouldBeUpper = shiftState == ShiftState.ON ||
                                       shiftState == ShiftState.TEMPORARY

                    val textToCommit = if (shouldBeUpper && char.isLetter()) {
                        char.uppercaseChar().toString()
                    } else {
                        char.toString()
                    }

                    inputConnection.commitText(textToCommit, 1)
                    Log.d(TAG, "Char: $textToCommit (from $char, shouldBeUpper=$shouldBeUpper)")

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

        Log.d(TAG, "Long press handled: $keyCode, shiftState=$shiftState, vibroEnabled=$vibroEnabled")

        val shouldBeUpper = shiftState == ShiftState.ON || shiftState == ShiftState.TEMPORARY

        // Вибро при долгом нажатии - ТОЛЬКО ЕСЛИ ВКЛЮЧЕНО
        if (vibroEnabled) {
            vibrateShort()
        }

        when (keyCode) {
            // Русские
            1077 -> { // е
                val result = if (shouldBeUpper) "Ё" else "ё"
                inputConnection.commitText(result, 1)
                Log.d(TAG, "Long press: е -> $result")
            }
            1080 -> { // и
                val result = if (shouldBeUpper) "Й" else "й"
                inputConnection.commitText(result, 1)
                Log.d(TAG, "Long press: и -> $result")
            }
            1100 -> { // ь
                val result = if (shouldBeUpper) "Ъ" else "ъ"
                inputConnection.commitText(result, 1)
                Log.d(TAG, "Long press: ь -> $result")
            }
            1093 -> { // х
                val result = if (shouldBeUpper) "Э" else "э"
                inputConnection.commitText(result, 1)
                Log.d(TAG, "Long press: х -> $result")
            }
            // Точка/запятая
            44 -> { // ,
                inputConnection.commitText(".", 1)
                Log.d(TAG, "Long press: , -> .")
            }
            46 -> { // .
                inputConnection.commitText(",", 1)
                Log.d(TAG, "Long press: . -> ,")
            }
            // Служебные
            -4 -> { // !? -> emoji mode
                currentMode = "emoji"
                loadKeyboard(currentLanguage, currentMode)
                Log.d(TAG, "Long press: !? -> emoji mode")
            }
            // Английские
            101 -> { // e
                val result = if (shouldBeUpper) "Ё" else "ё"
                inputConnection.commitText(result, 1)
                Log.d(TAG, "Long press: e -> $result")
            }
        }

        if (shiftState == ShiftState.TEMPORARY) {
            shiftState = ShiftState.OFF
            updateShiftIndicator()
        }
    }

    override fun onText(text: CharSequence?) {
        currentInputConnection?.commitText(text, 1)
        Log.d(TAG, "onText: $text")
    }

    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}