package com.example.keyboard

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.min

class DictionaryManager(private val context: Context) {

    // Словарь: слово -> частота
    private val wordFrequency = mutableMapOf<String, Int>()

    // Для быстрого поиска по префиксу
    private val prefixMap = mutableMapOf<String, MutableList<Pair<String, Int>>>()

    // Кэш последних исправлений для отмены
    private val lastCorrectionMap = mutableMapOf<String, String>()

    // Максимальное количество подсказок
    private val maxSuggestions = 3

    // Статистика загрузки
    var loadedWordsCount = 0
        private set

    init {
        loadDictionary()
    }

    private fun loadDictionary() {
        try {
            val startTime = System.currentTimeMillis()

            val inputStream = context.assets.open("rus_news_2024_300K-words.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))

            var lineCount = 0
            var validWordCount = 0

            reader.useLines { lines ->
                lines.forEach { line ->
                    try {
                        lineCount++

                        if (lineCount % 50000 == 0) {
                            Log.d("Dictionary", "Loading... $lineCount lines processed")
                        }

                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 3) {
                            val word = parts[1]
                            val frequency = parts[2].toInt()

                            // Берем только отдельные слова (без пробелов)
                            if (!word.contains(" ") && !word.contains("-") && word.length in 2..20) {

                                wordFrequency[word] = frequency

                                // Индексируем только частотные слова
                                if (frequency > 5 || word.length <= 5) {
                                    for (i in 1..minOf(4, word.length)) {
                                        val prefix = word.substring(0, i).lowercase()
                                        prefixMap.getOrPut(prefix) { mutableListOf() }
                                            .add(Pair(word, frequency))
                                    }
                                }

                                validWordCount++
                            }
                        }
                    } catch (e: Exception) {
                        // Пропускаем проблемные строки
                    }
                }
            }

            // Сортируем списки по частоте
            prefixMap.forEach { (_, list) ->
                list.sortByDescending { it.second }
            }

            val loadTime = System.currentTimeMillis() - startTime
            loadedWordsCount = validWordCount

            Log.d("Dictionary", "Loaded $validWordCount valid words out of $lineCount lines in ${loadTime}ms")

        } catch (e: Exception) {
            Log.e("Dictionary", "Failed to load dictionary", e)
        }
    }

    /**
     * Получить подсказки для автодополнения
     */
    fun getSuggestions(prefix: String): List<String> {
        if (prefix.length < 2) return emptyList()

        val lowerPrefix = prefix.lowercase()
        return prefixMap[lowerPrefix]
            ?.take(maxSuggestions)
            ?.map { it.first } ?: emptyList()
    }

    /**
     * Исправить слово (автокоррекция)
     * Возвращает Pair(исправленное слово, исходное слово) для возможности отмены
     */
    fun correctWord(word: String): Pair<String?, String> {
        if (word.length < 3) return Pair(null, word)

        val lowerWord = word.lowercase()

        // Если слово уже есть в словаре - не исправляем
        if (wordFrequency.containsKey(lowerWord)) {
            return Pair(null, word)
        }

        // Получаем кандидаты с похожей длиной
        val candidates = wordFrequency.keys.filter { dictWord ->
            kotlin.math.abs(dictWord.length - lowerWord.length) <= 2
        }.take(1000) // Ограничиваем для производительности

        // Вычисляем расстояние Левенштейна для каждого кандидата
        val scoredCandidates = mutableListOf<Triple<String, Int, Int>>()

        for (candidate in candidates) {
            val distance = levenshteinDistance(lowerWord, candidate.lowercase())

            // Динамический порог в зависимости от длины слова
            val maxDistance = when {
                lowerWord.length <= 4 -> 1
                lowerWord.length <= 6 -> 2
                else -> 3
            }

            if (distance <= maxDistance) {
                val frequency = wordFrequency[candidate] ?: 0
                scoredCandidates.add(Triple(candidate, distance, frequency))
            }
        }

        if (scoredCandidates.isEmpty()) return Pair(null, word)

        // Сортируем: сначала по расстоянию, потом по частоте
        scoredCandidates.sortWith(compareBy(
            { it.second },  // расстояние (меньше лучше)
            { -it.third }    // частота (больше лучше)
        ))

        val bestCandidate = scoredCandidates.first().first

        // Сохраняем оригинал для возможности отмены
        lastCorrectionMap[bestCandidate] = word

        // Сохраняем регистр первой буквы
        val result = if (word[0].isUpperCase())
            bestCandidate.replaceFirstChar { it.uppercase() }
        else
            bestCandidate

        return Pair(result, word)
    }

    /**
     * Отменить последнюю автокоррекцию для слова
     */
    fun undoCorrection(correctedWord: String): String? {
        return lastCorrectionMap[correctedWord.lowercase()]
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
        return wordFrequency.containsKey(word.lowercase())
    }
}