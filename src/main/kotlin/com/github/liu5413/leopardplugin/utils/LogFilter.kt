package com.github.liu5413.leopardplugin.utils

enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR
}

/**
 * Represents a segment of a log line after splitting by delimiters.
 * If isDelimiter is true, delimiterIndex indicates which delimiter matched (for color cycling).
 */
data class LogSegment(val text: String, val isDelimiter: Boolean, val delimiterIndex: Int = -1)

object LogFilter {

    fun segmentString(input: String, delimiters: List<String>): List<LogSegment> {
        if (input.isEmpty()) return emptyList()
        if (delimiters.isEmpty()) return listOf(LogSegment(input, false))

        val result = mutableListOf<LogSegment>()
        var startIndex = 0
        var currentIndex = 0

        while (currentIndex < input.length) {
            var delimiterFound = false
            for ((index, delimiter) in delimiters.withIndex()) {
                if (delimiter.isNotEmpty() && input.startsWith(delimiter, currentIndex)) {
                    if (currentIndex > startIndex) {
                        result.add(LogSegment(input.substring(startIndex, currentIndex), false))
                    }
                    result.add(LogSegment(delimiter, true, index))
                    currentIndex += delimiter.length
                    startIndex = currentIndex
                    delimiterFound = true
                    break
                }
            }
            if (!delimiterFound) {
                currentIndex++
            }
        }

        if (startIndex < input.length) {
            result.add(LogSegment(input.substring(startIndex), false))
        }

        return result
    }

    /**
     * Detect log level based on Android logcat format:
     * V/ = VERBOSE (white)
     * D/ = DEBUG (dark green)
     * I/ = INFO (white)
     * W/ = WARN (yellow)
     * E/ = ERROR (red)
     * Exception/Crash = ERROR (red)
     * Default = blue
     */
    fun detectLevel(line: String): LogLevel {
        return when {
            line.contains(" E/") || line.contains(" E ") ||
                line.contains("Exception") || line.contains("Crash") -> LogLevel.ERROR
            line.contains(" W/") || line.contains(" W ") -> LogLevel.WARN
            line.contains(" I/") || line.contains(" I ") -> LogLevel.INFO
            line.contains(" D/") || line.contains(" D ") -> LogLevel.DEBUG
            line.contains(" V/") || line.contains(" V ") -> LogLevel.VERBOSE
            else -> LogLevel.VERBOSE // Default to blue (VERBOSE)
        }
    }

    fun filterLines(
        lines: List<String>,
        delimiters: List<String>,
        contains: List<String>
    ): List<IndexedValue<String>> {
        val keywords = delimiters + contains
        if (keywords.isEmpty()) {
            return lines.withIndex().map { IndexedValue(it.index, it.value) }
        }
        return lines.withIndex()
            .filter { (_, line) -> keywords.any { line.contains(it) } }
            .map { IndexedValue(it.index, it.value) }
    }
}