package com.example.keyboard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import android.os.Handler
import android.os.Looper


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
        if (word.length < 3 || !isLoaded) {
            Log.d("Dictionary", "Skip correction: word=$word, isLoaded=$isLoaded")
            return null
        }

        val lowerWord = word.lowercase()
        Log.d("Dictionary", "Trying to correct: $lowerWord")

        // Если слово уже есть в словаре - не исправляем
        if (isWordInDictionary(lowerWord)) {
            Log.d("Dictionary", "Word already in dictionary: $lowerWord")
            return null
        }

        // Получаем кандидатов из базы данных
        val candidates = getCandidatesFromDB(lowerWord)

        if (candidates.isEmpty()) {
            Log.d("Dictionary", "No candidates found")
            return null
        }

        // Вычисляем расстояние Левенштейна для кандидатов
        val scoredCandidates = mutableListOf<Triple<String, Int, Int>>()

        for (candidate in candidates) {
            val distance = levenshteinDistance(lowerWord, candidate.lowercase())

            // Динамический порог
            val maxDistance = when {
                lowerWord.length <= 4 -> 1
                lowerWord.length <= 6 -> 2
                else -> 3
            }

            if (distance <= maxDistance) {
                val frequency = getWordFrequency(candidate) ?: 0
                scoredCandidates.add(Triple(candidate, distance, frequency))
                Log.d("Dictionary", "Candidate $candidate: distance=$distance, frequency=$frequency")
            }
        }

        if (scoredCandidates.isEmpty()) {
            Log.d("Dictionary", "No candidates within distance threshold")
            return null
        }


        scoredCandidates.sortWith { a, b ->
            when {
                a.second != b.second -> a.second.compareTo(b.second) // сначала расстояние
                a.first.first() == word.first() && b.first.first() != word.first() -> -1 // приоритет если первая буква совпадает
                a.first.first() != word.first() && b.first.first() == word.first() -> 1
                else -> b.third.compareTo(a.third) // потом частота
            }
        }

        val bestCandidate = scoredCandidates.first().first
        Log.d("Dictionary", "Best candidate: $bestCandidate")

        // Сохраняем информацию для отмены
        val correctedWithCase = preserveCase(word, bestCandidate)

        lastCorrection = CorrectionInfo(
            originalWord = word,
            correctedWord = correctedWithCase,
            position = cursorPosition - word.length
        )

        lastCorrectionMap[correctedWithCase.lowercase()] = lastCorrection!!

        return correctedWithCase
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
    val candidates = mutableSetOf<String>() // Set чтобы избежать повторов

    Log.d("Dictionary", "Ищем кандидаты для: $word")

    // 1. Поиск по точному совпадению (вдруг слово уже есть)
    var cursor = db.query(
        DictionaryDBHelper.TABLE_DICTIONARY,
        arrayOf(DictionaryDBHelper.COLUMN_WORD),
        "${DictionaryDBHelper.COLUMN_WORD} = ?",
        arrayOf(word),
        null, null, null
    )
    while (cursor.moveToNext()) {
        candidates.add(cursor.getString(0))
        Log.d("Dictionary", "Точное совпадение: ${cursor.getString(0)}")
    }
    cursor.close()

    // 2. Поиск с одной заменой (шаблон с _)
    // превет -> _ревет, п_евет, пр_вет, пре_ет, прев_т, преве_
    for (i in word.indices) {
        val pattern = word.substring(0, i) + "_" + word.substring(i + 1)
        cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD),
            "${DictionaryDBHelper.COLUMN_WORD} LIKE ?",
            arrayOf(pattern),
            null, null, null
        )
        while (cursor.moveToNext()) {
            candidates.add(cursor.getString(0))
            Log.d("Dictionary", "По маске $pattern: ${cursor.getString(0)}")
        }
        cursor.close()
    }

    // 3. Поиск с перестановкой соседних букв (частые ошибки)
    // превет -> рпевет, первет, првеет, прееВт и т.д.
    for (i in 0 until word.length - 1) {
        val swapped = word.substring(0, i) + word[i+1] + word[i] + word.substring(i + 2)
        cursor = db.query(
            DictionaryDBHelper.TABLE_DICTIONARY,
            arrayOf(DictionaryDBHelper.COLUMN_WORD),
            "${DictionaryDBHelper.COLUMN_WORD} = ?",
            arrayOf(swapped),
            null, null, null
        )
        while (cursor.moveToNext()) {
            candidates.add(cursor.getString(0))
            Log.d("Dictionary", "Перестановка: ${cursor.getString(0)}")
        }
        cursor.close()
    }

    // 4. Поиск с пропущенной буквой (если слово короче)
    if (word.length > 3) {
        for (i in word.indices) {
            val withoutChar = word.substring(0, i) + word.substring(i + 1)
            cursor = db.query(
                DictionaryDBHelper.TABLE_DICTIONARY,
                arrayOf(DictionaryDBHelper.COLUMN_WORD),
                "${DictionaryDBHelper.COLUMN_WORD} = ?",
                arrayOf(withoutChar),
                null, null, null
            )
            while (cursor.moveToNext()) {
                candidates.add(cursor.getString(0))
                Log.d("Dictionary", "Без буквы: ${cursor.getString(0)}")
            }
            cursor.close()
        }
    }

    // 5. Поиск с лишней буквой (если слово длиннее)
    for (c in 'а'..'я') {
        for (i in 0..word.length) {
            val withChar = word.substring(0, i) + c + word.substring(i)
            cursor = db.query(
                DictionaryDBHelper.TABLE_DICTIONARY,
                arrayOf(DictionaryDBHelper.COLUMN_WORD),
                "${DictionaryDBHelper.COLUMN_WORD} = ?",
                arrayOf(withChar),
                null, null, null
            )
            while (cursor.moveToNext()) {
                candidates.add(cursor.getString(0))
                Log.d("Dictionary", "С буквой $c: ${cursor.getString(0)}")
            }
            cursor.close()
        }
    }

    db.close()

    Log.d("Dictionary", "Всего найдено кандидатов: ${candidates.size}")
    return candidates.toList()
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
}