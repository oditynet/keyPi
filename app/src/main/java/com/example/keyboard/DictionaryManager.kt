package com.example.keyboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.collections.getOrNull
import com.example.keyboard.MyKeyboardView.TouchPosition

private val keyboardNeighbors = mapOf(
    // Русская раскладка
    'а' to setOf('п', 'в', 'ы', 'ф', 'я', 'ч', 'й'),
    'б' to setOf('и', 'ю', 'ь', 'н'),
    'в' to setOf('а', 'п', 'с', 'ф', 'я'),
    'г' to setOf('н', 'ш', 'к'),
    'д' to setOf('л', 'о', 'в', 'ж', 'э'),
    'е' to setOf('н', 'к', 'у', 'ё'),  // ё рядом с е
    'ё' to setOf('е'),
    'ж' to setOf('э', 'д', 'о'),
    'з' to setOf('щ', 'х', 'ш'),
    'и' to setOf('т', 'с', 'м', 'б', 'й'),
    'й' to setOf('ц', 'и', 'а'),
    'к' to setOf('у', 'е', 'г', 'р', 'п'),
    'л' to setOf('д', 'о', 'э'),
    'м' to setOf('и', 'с', 'я'),
    'н' to setOf('г', 'к', 'е', 'и'),
    'о' to setOf('р', 'л', 'д', 'щ', 'ш'),
    'п' to setOf('а', 'р', 'в', 'г', 'к'),
    'р' to setOf('о', 'п', 'а', 'н', 'г'),
    'с' to setOf('м', 'и', 'а', 'в', 'ф'),
    'т' to setOf('и', 'м', 'ь', 'з'),
    'у' to setOf('к', 'е', 'н'),
    'ф' to setOf('ы', 'а', 'с'),
    'х' to setOf('ъ', 'з', 'щ'),
    'ц' to setOf('у', 'й', 'щ'),
    'ч' to setOf('с', 'я', 'а'),
    'ш' to setOf('щ', 'г', 'о'),
    'щ' to setOf('ш', 'ч', 'ц', 'з'),
    'ъ' to setOf('х', 'э'),
    'ы' to setOf('в', 'а', 'ф'),
    'ь' to setOf('б', 'т', 'и'),
    'э' to setOf('ж', 'л', 'д', 'ъ'),
    'ю' to setOf('б', 'и'),
    'я' to setOf('а', 'ч', 'м', 'в'),

    // Английская раскладка
    'q' to setOf('w', 'a'),
    'w' to setOf('q', 'e', 's', 'a'),
    'e' to setOf('w', 'r', 'd', 's'),
    'r' to setOf('e', 't', 'f', 'd'),
    't' to setOf('r', 'y', 'g', 'f'),
    'y' to setOf('t', 'u', 'h', 'g'),
    'u' to setOf('y', 'i', 'j', 'h'),
    'i' to setOf('u', 'o', 'k', 'j'),
    'o' to setOf('i', 'p', 'l', 'k'),
    'p' to setOf('o', 'l'),
    'a' to setOf('q', 'w', 's', 'z'),
    's' to setOf('w', 'e', 'd', 'x', 'z', 'a'),
    'd' to setOf('e', 'r', 'f', 'c', 'x', 's'),
    'f' to setOf('r', 't', 'g', 'v', 'c', 'd'),
    'g' to setOf('t', 'y', 'h', 'b', 'v', 'f'),
    'h' to setOf('y', 'u', 'j', 'n', 'b', 'g'),
    'j' to setOf('u', 'i', 'k', 'm', 'n', 'h'),
    'k' to setOf('i', 'o', 'l', 'm', 'j'),
    'l' to setOf('o', 'p', 'k'),
    'z' to setOf('a', 's', 'x'),
    'x' to setOf('z', 's', 'd', 'c'),
    'c' to setOf('x', 'd', 'f', 'v'),
    'v' to setOf('c', 'f', 'g', 'b'),
    'b' to setOf('v', 'g', 'h', 'n'),
    'n' to setOf('b', 'h', 'j', 'm'),
    'm' to setOf('n', 'j', 'k')
)
class DictionaryManager(private val context: Context) {

    private lateinit var dbHelper: DictionaryDBHelper
    private var isLoaded = false

    // Кэш для быстрого доступа к частотным словам
    private val frequencyCache = mutableMapOf<String, Int>()

    // Кэш последних исправлений для отмены
    private val lastCorrectionMap = mutableMapOf<String, CorrectionInfo>()


    // Информация о текущем исправлении
    data class CorrectionInfo(
        val originalWord: String,
        val correctedWord: String,
        val position: Int // позиция слова в тексте
    )

    // Последнее исправленное слово для отмены
    private var lastCorrection: CorrectionInfo? = null

    // Максимальное количество подсказок
    private val maxSuggestions = 3

    // Статистика загрузки
    var loadedWordsCount = 0
        private set

    init {
        dbHelper = DictionaryDBHelper(context)
        loadDictionaryAsync()
    }

    private fun loadDictionaryAsync() {
    Thread {
        try {
            val startTime = System.currentTimeMillis()

            // Проверяем, загружен ли уже словарь
            if (dbHelper.getWordCount() > 10000) {
                isLoaded = true
                loadedWordsCount = dbHelper.getWordCount()
                Log.d("Dictionary", "Dictionary already loaded: $loadedWordsCount words")
                return@Thread
            }



            val inputStream = context.resources.openRawResource(R.raw.rus_news_2024_300k_words)
            val reader = BufferedReader(InputStreamReader(inputStream))

            val db = dbHelper.writableDatabase
            db.beginTransaction()
            showNotification("📚 Загрузка", "Начинаю загрузку словаря...")

            try {
                var lineCount = 0
                var validWordCount = 0

                val batchSize = 5000  // увеличил для скорости
                val contentValuesList = mutableListOf<ContentValues>()

                reader.useLines { lines ->
                    lines.forEach { line ->
                        try {
                            lineCount++

                            // ПРОСТО БЕРЕМ ВСЮ СТРОКУ - ЭТО СЛОВО
                            val word = line.trim().lowercase()

                            // Базовая проверка
                            if (word.isNotEmpty() && word.length in 2..20) {

                                val contentValues = ContentValues().apply {
                                    put(DictionaryDBHelper.COLUMN_WORD, word)
                                    put(DictionaryDBHelper.COLUMN_FREQUENCY, 1) // частота не важна
                                }
                                contentValuesList.add(contentValues)
                                validWordCount++

                                if (contentValuesList.size >= batchSize) {
                                    insertBatch(db, contentValuesList)
                                    contentValuesList.clear()
                                }
                            }

                            if (lineCount % 50000 == 0) {
                                Log.d("Dictionary", "Loading... $lineCount lines processed, $validWordCount words added")
                            }

                        } catch (e: Exception) {
                            // Пропускаем проблемные строки
                        }
                    }
                }

                if (contentValuesList.isNotEmpty()) {
                    insertBatch(db, contentValuesList)
                }

                db.setTransactionSuccessful()

                val loadTime = System.currentTimeMillis() - startTime
                loadedWordsCount = validWordCount
                isLoaded = true

                Log.d("Dictionary", "Loaded $validWordCount words in ${loadTime}ms")



            } catch (e: Exception) {
                Log.e("Dictionary", "Error loading dictionary", e)
            } finally {
                db.endTransaction()
                db.close()
            }
        } catch (e: Exception) {
            Log.e("Dictionary", "Failed to load dictionary", e)
        }
    }.start()
}


    private fun insertBatch(db: SQLiteDatabase, valuesList: List<ContentValues>) {
        valuesList.forEach { values ->
            db.insertWithOnConflict(DictionaryDBHelper.TABLE_DICTIONARY,
                null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /**
     * Получить подсказки для автодополнения
     */
    fun getSuggestions(prefix: String): List<String> {
        if (prefix.length < 2 || !isLoaded) return emptyList()

        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD),
            "${DictionaryDBHelper.COLUMN_WORD} LIKE ?",
            arrayOf("$prefix%"),
            null, null,
            "${DictionaryDBHelper.COLUMN_FREQUENCY} DESC",
            maxSuggestions.toString()
        )

        val suggestions = mutableListOf<String>()
        while (cursor.moveToNext()) {
            suggestions.add(cursor.getString(0))
        }
        cursor.close()
        db.close()

        return suggestions
    }

    /**
     * Исправить слово (автокоррекция)
     * Возвращает исправленное слово или null
     */
    fun correctWord(word: String, cursorPosition: Int = -1): String? {
        if (word.length < 2 || !isLoaded) {
            return null
        }

        val lowerWord = word.lowercase()

        // Если слово уже есть в словаре - не исправляем
        if (isWordInDictionary(lowerWord)) {
            return null
        }

        // Получаем кандидатов
        val candidates = getCandidatesFromDB(lowerWord)

        if (candidates.isEmpty()) {
            return null
        }

        // Оцениваем каждый кандидат
        val scoredCandidates = candidates.map { candidate ->
            val distance = levenshteinDistance(lowerWord, candidate.lowercase())
            val keyboardScore = calculateKeyboardScore(lowerWord, candidate.lowercase())
            Triple(candidate, distance, keyboardScore)
        }

        // Сортируем: сначала по расстоянию Левенштейна, потом по клавиатурному скору
        val bestCandidate = scoredCandidates.minBy { (_, distance, keyboardScore) ->
            distance * 10 + keyboardScore
        }.first

        // Сохраняем для отмены
        lastCorrection = CorrectionInfo(
            originalWord = word,
            correctedWord = preserveCase(word, bestCandidate),
            position = cursorPosition - word.length
        )

        return lastCorrection!!.correctedWord
    }
    private fun calculateKeyboardScore(original: String, candidate: String): Int {
        var score = 0
        val minLength = minOf(original.length, candidate.length)

        for (i in 0 until minLength) {
            if (original[i] != candidate[i]) {
                val neighbors = keyboardNeighbors[original[i]] ?: continue
                if (candidate[i] in neighbors) {
                    score += 1 // Хорошо, это соседняя клавиша
                } else {
                    score += 10 // Плохо, это далекая клавиша
                }
            }
        }

        // Штраф за разную длину
        score += Math.abs(original.length - candidate.length) * 5

        return score
    }

    fun debugDictionary() {
    Thread {
        val db = dbHelper.readableDatabase

        // Проверяем общее количество слов
        var cursor = db.rawQuery("SELECT COUNT(*) FROM ${DictionaryDBHelper.TABLE_DICTIONARY}", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        Log.d("Dictionary", "TOTAL WORDS IN DB: $count")

        // Показываем первые 10 слов
        cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD, DictionaryDBHelper.COLUMN_FREQUENCY),
            null, null, null, null,
            "${DictionaryDBHelper.COLUMN_FREQUENCY} DESC",
            "10"
        )

        Log.d("Dictionary", "First 10 words by frequency:")
        while (cursor.moveToNext()) {
            val word = cursor.getString(0)
            val freq = cursor.getInt(1)
            Log.d("Dictionary", "  $word ($freq)")
        }
        cursor.close()

        // Проверяем, есть ли слово "привет"
        cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD),
            "${DictionaryDBHelper.COLUMN_WORD} = ?",
            arrayOf("привет"),
            null, null, null
        )
        val hasPrivet = cursor.count > 0
        cursor.close()
        Log.d("Dictionary", "Word 'привет' in DB: $hasPrivet")

        // Проверяем слова с длиной 5-6 букв
        cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD),
            "LENGTH(${DictionaryDBHelper.COLUMN_WORD}) BETWEEN 5 AND 6",
            null, null, null,
            null, "20"
        )

        Log.d("Dictionary", "Sample of 20 words with length 5-6:")
        while (cursor.moveToNext()) {
            val word = cursor.getString(0)
            Log.d("Dictionary", "  $word")
        }
        cursor.close()

        db.close()
    }.start()
}

    /**
     * Получить информацию о последнем исправлении для отмены
     */
    fun getLastCorrectionForUndo(correctedWord: String? = null): CorrectionInfo? {
        return if (correctedWord != null) {
            lastCorrectionMap[correctedWord.lowercase()]
        } else {
            lastCorrection
        }
    }

    /**
     * Очистить информацию об исправлении после отмены
     */
    fun clearLastCorrection(correctedWord: String) {
        lastCorrectionMap.remove(correctedWord.lowercase())
        if (lastCorrection?.correctedWord?.lowercase() == correctedWord.lowercase()) {
            lastCorrection = null
        }
    }

    private fun getCandidatesFromDB(word: String): List<String> {
        val db = dbHelper.readableDatabase
        val candidates = mutableSetOf<String>()

        Log.d("Dictionary", "Ищем кандидаты для: $word")

        // 1. Сначала проверяем точное совпадение
        checkExactMatch(db, word, candidates)

        // 2. Генерируем варианты на основе соседних клавиш
        val neighborVariations = generateNeighborVariations(word)
        for (variation in neighborVariations) {
            checkExactMatch(db, variation, candidates)
        }

        // 3. Проверяем варианты с удалением буквы (если слово длинное)
        if (word.length > 4) {
            for (i in word.indices) {
                val withoutChar = word.substring(0, i) + word.substring(i + 1)
                checkExactMatch(db, withoutChar, candidates)
            }
        }

        // 4. Проверяем варианты с вставкой буквы (если слово короткое)
        if (word.length < 8) {
            for (i in 0..word.length) {
                for (c in 'а'..'я') {
                    val withChar = word.substring(0, i) + c + word.substring(i)
                    checkExactMatch(db, withChar, candidates)
                }
            }
        }

        // 5. Дополнительно: проверяем с перестановкой соседних букв
        for (i in 0 until word.length - 1) {
            val swapped = word.substring(0, i) + word[i+1] + word[i] + word.substring(i + 2)
            checkExactMatch(db, swapped, candidates)
        }

        db.close()

        Log.d("Dictionary", "Всего найдено кандидатов: ${candidates.size}")
        return candidates.toList()
    }

    private fun checkExactMatch(db: SQLiteDatabase, searchWord: String, candidates: MutableSet<String>) {
        val cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD),
            "${DictionaryDBHelper.COLUMN_WORD} = ?",
            arrayOf(searchWord),
            null, null, null
        )
        while (cursor.moveToNext()) {
            candidates.add(cursor.getString(0))
        }
        cursor.close()
    }

    private fun getWordFrequency(word: String): Int? {
        // Сначала проверяем кэш
        frequencyCache[word]?.let { return it }

        // Ищем в БД
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_FREQUENCY),
            "${DictionaryDBHelper.COLUMN_WORD} = ?",
            arrayOf(word),
            null, null, null
        )

        var frequency: Int? = null
        if (cursor.moveToFirst()) {
            frequency = cursor.getInt(0)
            // Добавляем в кэш
            frequencyCache[word] = frequency
        }
        cursor.close()
        db.close()

        return frequency
    }

    private fun preserveCase(original: String, corrected: String): String {
        return when {
            original.all { it.isUpperCase() } -> corrected.uppercase()
            original[0].isUpperCase() -> corrected.replaceFirstChar { it.uppercase() }
            else -> corrected.lowercase()
        }
    }

    /**
     * Алгоритм расстояния Левенштейна
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i-1] == s2[j-1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i-1][j] + 1,
                    dp[i][j-1] + 1,
                    dp[i-1][j-1] + cost
                )
            }
        }

        return dp[s1.length][s2.length]
    }

    /**
     * Проверить, есть ли слово в словаре
     */
    fun isWordInDictionary(word: String): Boolean {
        if (frequencyCache.containsKey(word)) return true

        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD),
            "${DictionaryDBHelper.COLUMN_WORD} = ?",
            arrayOf(word),
            null, null, null
        )
        val exists = cursor.count > 0
        cursor.close()
        db.close()

        return exists
    }

    /**
     * Проверить, загружен ли словарь
     */
    fun isLoaded(): Boolean = isLoaded

    class DictionaryDBHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        companion object {
            const val DATABASE_NAME = "dictionary.db"
            const val DATABASE_VERSION = 1
            const val TABLE_DICTIONARY = "dictionary"
            const val COLUMN_ID = "_id"
            const val COLUMN_WORD = "word"
            const val COLUMN_FREQUENCY = "frequency"
        }

        override fun onCreate(db: SQLiteDatabase) {
            Log.d("Dictionary", "Creating database...")

            val createTable = """
        CREATE TABLE $TABLE_DICTIONARY (
            $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COLUMN_WORD TEXT UNIQUE,
            $COLUMN_FREQUENCY INTEGER
        )
    """.trimIndent()
            db.execSQL(createTable)

            // Создаем индексы для быстрого поиска
            db.execSQL("CREATE INDEX idx_word ON $TABLE_DICTIONARY($COLUMN_WORD)")
            db.execSQL("CREATE INDEX idx_frequency ON $TABLE_DICTIONARY($COLUMN_FREQUENCY DESC)")

            Log.d("Dictionary", "Database created successfully")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.d("Dictionary", "Upgrading database from $oldVersion to $newVersion")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DICTIONARY")
            onCreate(db)
        }

        fun getWordCount(): Int {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DICTIONARY", null)
            var count = 0
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0)
            }
            cursor.close()
            db.close()
            return count
        }
    }

    private fun generateNeighborVariations(word: String, maxErrors: Int = 2): List<String> {
        val variations = mutableSetOf<String>()

        // Генерируем варианты с заменой одной буквы на соседнюю
        for (i in word.indices) {
            val originalChar = word[i]
            val neighbors = keyboardNeighbors[originalChar] ?: continue

            for (neighbor in neighbors) {
                val variation = word.substring(0, i) + neighbor + word.substring(i + 1)
                variations.add(variation)
            }
        }

        // Если нужно больше вариантов, генерируем с двумя заменами
        if (maxErrors >= 2) {
            val singleVariations = variations.toList()
            for (firstVar in singleVariations) {
                for (i in firstVar.indices) {
                    val originalChar = firstVar[i]
                    val neighbors = keyboardNeighbors[originalChar] ?: continue

                    for (neighbor in neighbors) {
                        val secondVar = firstVar.substring(0, i) + neighbor + firstVar.substring(i + 1)
                        variations.add(secondVar)
                    }
                }
            }
        }

        return variations.toList()
    }

    private fun showNotification(title: String, message: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // Создаем канал для Android 8+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        "dictionary_channel",
                        "Словарь",
                        NotificationManager.IMPORTANCE_LOW
                    )
                    notificationManager.createNotificationChannel(channel)
                }

                // Создаем уведомление
                val notification = NotificationCompat.Builder(context, "dictionary_channel")
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .build()

                // Показываем
                NotificationManagerCompat.from(context).notify(1001, notification)

            } catch (e: Exception) {
                Log.e("Dictionary", "Notification error: ${e.message}")
            }
        }
    }

    private val directionalNeighbors = mapOf(
        // Русская раскладка - только самые вероятные замены
        'а' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('й', 'ч'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('я'),
            MyKeyboardView.TouchPosition.LEFT to setOf('ы'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('п')
        ),
        'п' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('к'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('р'),
            MyKeyboardView.TouchPosition.LEFT to setOf('а'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('р')
        ),
        'р' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('е'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('о'),
            MyKeyboardView.TouchPosition.LEFT to setOf('п'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('о')
        ),
        'о' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('н'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('л'),
            MyKeyboardView.TouchPosition.LEFT to setOf('р'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('л')
        ),
        'л' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('г'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('д'),
            MyKeyboardView.TouchPosition.LEFT to setOf('о'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('д')
        ),
        'д' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('л'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('ж'),
            MyKeyboardView.TouchPosition.LEFT to setOf('л'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('ж')
        ),
        'к' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('у'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('п'),
            MyKeyboardView.TouchPosition.LEFT to setOf('у'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('п')
        ),
        'е' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('ё'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('р'),
            MyKeyboardView.TouchPosition.LEFT to setOf('к'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('н')
        ),
        'н' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('г'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('о'),
            MyKeyboardView.TouchPosition.LEFT to setOf('е'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('г')
        ),
        'и' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('й'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('т'),
            MyKeyboardView.TouchPosition.LEFT to setOf('с'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('т')
        ),
        'т' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('и'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('ь'),
            MyKeyboardView.TouchPosition.LEFT to setOf('и'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('ь')
        ),
        'с' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('ч'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('м'),
            MyKeyboardView.TouchPosition.LEFT to setOf('а'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('м')
        ),
        'м' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('с'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('я'),
            MyKeyboardView.TouchPosition.LEFT to setOf('с'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('я')
        ),
        'в' to mapOf(
            MyKeyboardView.TouchPosition.TOP to setOf('ц'),
            MyKeyboardView.TouchPosition.BOTTOM to setOf('а'),
            MyKeyboardView.TouchPosition.LEFT to setOf('ы'),
            MyKeyboardView.TouchPosition.RIGHT to setOf('а')
        )
    )
    fun correctWordWithPosition(word: String, touchPositions: List<TouchPosition>, cursorPosition: Int = -1): String? {
        if (word.length < 2 || !isLoaded) {
            return null
        }

        val lowerWord = word.lowercase()

        // Если слово уже есть - не исправляем
        if (isWordInDictionary(lowerWord)) {
            return null
        }

        // Генерируем варианты на основе позиций касания
        val candidates = mutableSetOf<String>()

        for (i in lowerWord.indices) {
            val originalChar = lowerWord[i]
            val touchPos = touchPositions.getOrNull(i) ?: MyKeyboardView.TouchPosition.CENTER

            // Получаем вероятные символы для этой позиции
            val probableChars = directionalNeighbors[originalChar]?.get(touchPos) ?: continue

            for (probableChar in probableChars) {
                val variation = lowerWord.substring(0, i) + probableChar + lowerWord.substring(i + 1)

                // Проверяем в базе
                if (isWordInDictionary(variation)) {
                    candidates.add(variation)
                }
            }
        }

        if (candidates.isEmpty()) {
            return null
        }

        // Выбираем лучший вариант (с наименьшим расстоянием Левенштейна)
        val bestCandidate = candidates.minByOrNull {
            levenshteinDistance(lowerWord, it)
        } ?: return null

        val correctedWithCase = preserveCase(word, bestCandidate)

        // Сохраняем для отмены
        lastCorrection = CorrectionInfo(
            originalWord = word,
            correctedWord = correctedWithCase,
            position = cursorPosition - word.length
        )
        lastCorrectionMap[correctedWithCase.lowercase()] = lastCorrection!!

        return correctedWithCase
    }
}