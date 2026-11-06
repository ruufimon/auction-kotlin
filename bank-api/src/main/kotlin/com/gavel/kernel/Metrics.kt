package com.gavel.kernel

import java.time.Duration

/** Metrics port so modules stay independent of the metrics library. */
interface Metrics {
    fun increment(name: String, vararg tags: Pair<String, String>)
    fun record(name: String, duration: Duration, vararg tags: Pair<String, String>)

    object None : Metrics {
        override fun increment(name: String, vararg tags: Pair<String, String>) = Unit
        override fun record(name: String, duration: Duration, vararg tags: Pair<String, String>) = Unit
    }
}
