package com.identium.nfc.util

/**
 * One-shot event wrapper for LiveData. Each observer gets the content
 * exactly once via [consume] — switching tabs and re-attaching observers
 * no longer re-toasts a stale result.
 */
class Event<out T>(private val content: T) {
    private var handled = false

    fun consume(): T? = if (handled) null else { handled = true; content }
    fun peek(): T = content
}
