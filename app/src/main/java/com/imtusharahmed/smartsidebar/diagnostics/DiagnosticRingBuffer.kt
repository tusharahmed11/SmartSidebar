package com.imtusharahmed.smartsidebar.diagnostics

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

data class DiagnosticEvent(
    val timestampEpochMs: Long,
    val category: String,
    val message: String
) {
    fun formatForDump(): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val timeString = formatter.format(Date(timestampEpochMs))
        return "[$timeString] [$category] $message"
    }
}

class DiagnosticRingBuffer(
    private val maxCapacity: Int = DEFAULT_CAPACITY
) {
    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(maxCapacity.coerceAtLeast(1))

    fun record(
        category: String,
        message: String,
        timestampEpochMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            if (buffer.size >= maxCapacity) {
                buffer.removeFirst()
            }
            buffer.addLast(
                DiagnosticEvent(
                    timestampEpochMs = timestampEpochMs,
                    category = category,
                    message = message
                )
            )
        }
    }

    fun snapshot(): List<DiagnosticEvent> {
        synchronized(lock) {
            return buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 50
    }
}

