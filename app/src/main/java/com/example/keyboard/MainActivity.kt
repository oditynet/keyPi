package com.example.keyboard

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    // UI элементы
    private lateinit var btnSettings: Button
    private lateinit var btnTestKeyboard: Button
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var sensitivityValue: TextView
    private lateinit var contextSwitch: SwitchMaterial
    private lateinit var themeSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var languageText: TextView
    private lateinit var cardSettings: CardView

    // Настройки
    private val prefs by lazy { getSharedPreferences("keyboard_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Применяем тему до загрузки layout
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadSettings()
        setupListeners()
        checkKeyboardEnabled()

        val density = resources.displayMetrics.density
Log.d("TEST", "Density = $density") // У вас в логах должно быть 3.0
        //updateLanguageInfo()
    }

    private fun initViews() {
        btnSettings = findViewById(R.id.btnSettings)
        btnTestKeyboard = findViewById(R.id.btnTestKeyboard)
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar)
        sensitivityValue = findViewById(R.id.sensitivityValue)
        contextSwitch = findViewById(R.id.contextSwitch)
        themeSwitch = findViewById(R.id.themeSwitch)
        statusText = findViewById(R.id.statusText)
            //languageText = findViewById(R.id.languageText)
        cardSettings = findViewById(R.id.cardSettings)
    }

    private fun loadSettings() {
        // Загружаем чувствительность
        val sensitivity = prefs.getInt("touch_sensitivity", 70)
        sensitivitySeekBar.progress = sensitivity
        sensitivityValue.text = "$sensitivity%"

        // Загружаем настройку контекста
        val useContext = prefs.getBoolean("use_context", true)
        contextSwitch.isChecked = useContext

        // Загружаем настройку темы
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        themeSwitch.isChecked = isDarkTheme
    }

    private fun setupListeners() {
        // Чувствительность касания
        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityValue.text = "$progress%"
                prefs.edit().putInt("touch_sensitivity", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Контекстное предсказание
        contextSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_context", isChecked).apply()
            Toast.makeText(this,
                if (isChecked) "🧠 Контекстное предсказание включено"
                else "📝 Контекстное предсказание отключено",
                Toast.LENGTH_SHORT).show()
        }

        // Переключение темы
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_theme", isChecked).apply()
            applyTheme()
            Toast.makeText(this,
                if (isChecked) "🌙 Тёмная тема включена"
                else "☀️ Светлая тема включена",
                Toast.LENGTH_SHORT).show()
            recreate() // Пересоздаём для применения темы
        }

        // Кнопка настроек клавиатуры
        btnSettings.setOnClickListener {
            showKeyboardSettings()
        }

        // Кнопка выбора клавиатуры
        btnTestKeyboard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    private fun applyTheme() {
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        if (isDarkTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    private fun showKeyboardSettings() {
        val languages = arrayOf("Русская (Диктор)", "Английская (QWERTY)")
        val currentLang = if (prefs.getString("keyboard_language", "en") == "ru") 0 else 1

        AlertDialog.Builder(this)
            .setTitle("⌨️ Настройки клавиатуры")
            .setSingleChoiceItems(languages, currentLang) { dialog, which ->
                val language = if (which == 0) "ru" else "en"
                prefs.edit().putString("keyboard_language", language).apply()
                    // updateLanguageInfo()
                dialog.dismiss()
                Toast.makeText(this, "Язык изменён на ${languages[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Дополнительно") { _, _ ->
                showAdvancedSettings()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showAdvancedSettings() {
        val items = arrayOf(
            "🎯 Калибровка сенсора",
            "🔊 Виброотклик",
            "📏 Размер клавиш",
            "🔄 Сброс настроек"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ Дополнительные настройки")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "Калибровка сенсора...", Toast.LENGTH_SHORT).show()
                    1 -> {
                        val vibro = !prefs.getBoolean("vibro", true)
                        prefs.edit().putBoolean("vibro", vibro).apply()
                        Toast.makeText(this,
                            if (vibro) "🔊 Виброотклик включён"
                            else "🔇 Виброотклик выключён",
                            Toast.LENGTH_SHORT).show()
                    }
                    2 -> showKeySizeDialog()
                    3 -> resetSettings()
                }
            }
            .setNegativeButton("Назад", null)
            .show()
    }

    private fun showKeySizeDialog() {
        val sizes = arrayOf("Маленький (5.5мм)", "Средний (6.6мм)", "Большой (7.7мм)")
        val currentSize = prefs.getInt("key_size", 1)

        AlertDialog.Builder(this)
            .setTitle("📏 Размер клавиш")
            .setSingleChoiceItems(sizes, currentSize) { dialog, which ->
                prefs.edit().putInt("key_size", which).apply()
                dialog.dismiss()
                Toast.makeText(this, "Размер изменён на ${sizes[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun resetSettings() {
        AlertDialog.Builder(this)
            .setTitle("🔄 Сброс настроек")
            .setMessage("Вы уверены? Все настройки будут сброшены к значениям по умолчанию.")
            .setPositiveButton("Сбросить") { _, _ ->
                prefs.edit().clear().apply()
                loadSettings()
                Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateLanguageInfo() {
        val lang = prefs.getString("keyboard_language", "en")
        val langName = if (lang == "ru") "Русская (Диктор)" else "Английская (QWERTY)"
        val layoutInfo = if (lang == "ru")
            "ЙЦУКЕН...\nФЫВАПРОЛДЖ\nЯЧСМИТЬБЮХ"
        else
            "QWERTYUIOP\nASDFGHJKL;\nZXCVBNM,./"

        languageText.text = """
            Текущий язык: $langName
            
            Раскладка:
            $layoutInfo

        """.trimIndent()
    }

    private fun checkKeyboardEnabled() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledServices = imm.enabledInputMethodList

        var ourKeyboardEnabled = false
        for (service in enabledServices) {
            if (service.packageName == packageName) {
                ourKeyboardEnabled = true
                break
            }
        }

        updateKeyboardStatus(ourKeyboardEnabled)

        if (!ourKeyboardEnabled) {
            showEnableKeyboardDialog()
        }
    }

    private fun updateKeyboardStatus(isEnabled: Boolean) {
        if (isEnabled) {
            statusText.text = "✅ Клавиатура включена и готова к работе"
            statusText.setTextColor(getColor(if (isDarkTheme())
                android.R.color.holo_green_light
                else android.R.color.holo_green_dark))
        } else {
            statusText.text = "❌ Клавиатура не включена. Нажмите 'Выбрать клавиатуру'"
            statusText.setTextColor(getColor(if (isDarkTheme())
                android.R.color.holo_red_light
                else android.R.color.holo_red_dark))
        }
    }

    private fun isDarkTheme(): Boolean {
        return resources.configuration.uiMode and
               Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showEnableKeyboardDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔌 Включите клавиатуру")
            .setMessage("""
                Чтобы использовать клавиатуру:
                
                1. Нажмите «Открыть настройки»
                2. Выберите «Виртуальная клавиатура» 
                3. Найдите «My Keyboard» и включите переключатель
                4. Вернитесь в приложение
                
                После включения клавиатуру можно выбрать в любом поле ввода.
            """.trimIndent())
            .setPositiveButton("🔓 Открыть настройки") { _, _ ->
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            .setNegativeButton("❌ Закрыть", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Проверяем статус при возвращении в приложение
        checkKeyboardEnabled()
       // updateLanguageInfo()
    }
}