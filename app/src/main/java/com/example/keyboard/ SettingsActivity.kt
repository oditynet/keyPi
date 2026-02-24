package com.example.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    companion object {
        // КЛЮЧИ ДОЛЖНЫ СОВПАДАТЬ С MyKeyboardService
        const val PREF_KEY_LANGUAGE = "keyboard_language"
        const val PREF_KEY_TOUCH_SENSITIVITY = "touch_sensitivity"
        const val PREF_KEY_USE_CONTEXT = "use_context"
        const val PREF_KEY_VIBRO = "vibro"
        const val PREF_KEY_KEY_SIZE = "key_size"
        const val PREF_KEY_DARK_THEME = "dark_theme"
        const val PREF_KEY_AUTO_CORRECT = "auto_correct"  // НОВЫЙ КЛЮЧ для автокоррекции

        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // ВАЖНО: используем ТОЧНО такое же имя файла как в сервисе
        prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)

        // Инициализация view
        val sensitivitySeekBar = findViewById<SeekBar>(R.id.sensitivitySeekBar)
        val sensitivityValue = findViewById<TextView>(R.id.sensitivityValue)
        val contextSwitch = findViewById<Switch>(R.id.contextSwitch)
        val vibroSwitch = findViewById<Switch>(R.id.vibroSwitch)
        val themeSwitch = findViewById<Switch>(R.id.themeSwitch)
        val keySizeSpinner = findViewById<Spinner>(R.id.keySizeSpinner)
        val autoCorrectSwitch = findViewById<Switch>(R.id.autoCorrectSwitch)  // НОВЫЙ Switch

        // Загрузка настроек
        val sensitivity = prefs.getInt(PREF_KEY_TOUCH_SENSITIVITY, 70)
        sensitivitySeekBar.progress = sensitivity
        sensitivityValue.text = "$sensitivity%"
        Log.d(TAG, "Loaded sensitivity: $sensitivity")

        val useContext = prefs.getBoolean(PREF_KEY_USE_CONTEXT, true)
        contextSwitch.isChecked = useContext
        Log.d(TAG, "Loaded context: $useContext")

        val vibroValue = prefs.getBoolean(PREF_KEY_VIBRO, true)
        vibroSwitch.isChecked = vibroValue
        Log.d(TAG, "Loaded vibro: $vibroValue")

        val darkTheme = prefs.getBoolean(PREF_KEY_DARK_THEME, false)
        themeSwitch.isChecked = darkTheme
        Log.d(TAG, "Loaded theme: $darkTheme")

        val keySize = prefs.getInt(PREF_KEY_KEY_SIZE, 1)
        keySizeSpinner.setSelection(keySize)
        Log.d(TAG, "Loaded keySize: $keySize")

        // НОВОЕ: загрузка настройки автокоррекции
        val autoCorrect = prefs.getBoolean(PREF_KEY_AUTO_CORRECT, true)  // по умолчанию включено
        autoCorrectSwitch.isChecked = autoCorrect
        Log.d(TAG, "Loaded auto correct: $autoCorrect")

        // Слушатели
        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityValue.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt(PREF_KEY_TOUCH_SENSITIVITY, sensitivitySeekBar.progress).apply()
                Log.d(TAG, "Saved sensitivity: ${sensitivitySeekBar.progress}")
                Toast.makeText(this@SettingsActivity, "Чувствительность: ${sensitivitySeekBar.progress}%", Toast.LENGTH_SHORT).show()
            }
        })

        contextSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_USE_CONTEXT, isChecked).apply()
            Log.d(TAG, "Saved context: $isChecked")
            Toast.makeText(this,
                if (isChecked) "Контекстное предсказание включено"
                else "Контекстное предсказание отключено",
                Toast.LENGTH_SHORT).show()
        }

        vibroSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_VIBRO, isChecked).apply()
            Log.d(TAG, "Saved vibro: $isChecked")
            Toast.makeText(this,
                if (isChecked) "Виброотклик включен"
                else "Виброотклик отключен",
                Toast.LENGTH_SHORT).show()
        }

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_DARK_THEME, isChecked).apply()
            Log.d(TAG, "Saved theme: $isChecked")
            Toast.makeText(this,
                if (isChecked) "Темная тема включена (перезапустите)"
                else "Светлая тема включена",
                Toast.LENGTH_SHORT).show()
        }

        // НОВЫЙ слушатель для автокоррекции
        autoCorrectSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_KEY_AUTO_CORRECT, isChecked).apply()
            Log.d(TAG, "Saved auto correct: $isChecked")
            Toast.makeText(this,
                if (isChecked) "Автокоррекция включена"
                else "Автокоррекция отключена",
                Toast.LENGTH_SHORT).show()
        }

        keySizeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(PREF_KEY_KEY_SIZE, position).apply()
                Log.d(TAG, "Saved keySize: $position")
                Toast.makeText(this@SettingsActivity,
                    when(position) {
                        0 -> "Маленький размер клавиш"
                        1 -> "Средний размер клавиш"
                        2 -> "Большой размер клавиш"
                        else -> ""
                    }, Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}