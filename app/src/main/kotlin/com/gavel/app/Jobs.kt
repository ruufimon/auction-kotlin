package com.gavel.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Background loops for the lifecycle jobs and the outbox dispatcher. Every job
 * coordinates through row locks, so running several instances is safe.
 */
class BackgroundJobs(private val jobs: List<Pair<String, Duration>>, private val run: (String) -> Unit) {
    private val log = LoggerFactory.getLogger(BackgroundJobs::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableListOf<Job>()

    fun start() {
        for ((name, interval) in jobs) {
            running += scope.launch {
                while (isActive) {
                    try {
                        run(name)
                    } catch (e: Exception) {
                        log.error("Background job {} failed; retrying in {}", name, interval, e)
                    }
                    delay(interval.toMillis())
                }
            }
        }
    }

    fun stop() = scope.cancel()
}
