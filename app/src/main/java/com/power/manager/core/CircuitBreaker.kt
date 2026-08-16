package com.power.manager.core

import java.util.concurrent.ConcurrentHashMap

object CircuitBreaker {
    private const val THRESHOLD = 5
    private val fails = ConcurrentHashMap<String, Int>()

    fun shouldBypass(action: String): Boolean = (fails[action] ?: 0) >= THRESHOLD

    fun recordSuccess(action: String) {
        fails.remove(action)
    }

    fun recordFailure(action: String) {
        fails.merge(action, 1, Int::plus)
    }
}