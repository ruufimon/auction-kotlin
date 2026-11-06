package com.gavel.kernel

import org.slf4j.MDC
import java.util.UUID

/** Correlation IDs travel through HTTP calls, events and provider calls via the logging MDC. */
object Correlation {
    const val MDC_KEY = "correlationId"
    const val HEADER = "X-Correlation-Id"

    fun current(): String = MDC.get(MDC_KEY) ?: newId()

    fun newId(): String = UUID.randomUUID().toString()

    inline fun <T> with(id: String, block: () -> T): T {
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, id)
        try {
            return block()
        } finally {
            if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
        }
    }
}
