package com.gavel.app

import com.gavel.kernel.Metrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.time.Duration

class MetricsAdapter(private val registry: MeterRegistry) : Metrics {
    override fun increment(name: String, vararg tags: Pair<String, String>) {
        registry.counter(name, tags.toTags()).increment()
    }

    override fun record(name: String, duration: Duration, vararg tags: Pair<String, String>) {
        registry.timer(name, tags.toTags()).record(duration.coerceAtLeast(Duration.ZERO))
    }

    private fun Array<out Pair<String, String>>.toTags(): Tags = Tags.of(*flatMap { listOf(it.first, it.second) }.toTypedArray())
}
