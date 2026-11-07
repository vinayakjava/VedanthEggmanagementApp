package com.eggbusinessmanager.domain.logging

/**
 * Logging port. Messages are lambdas so nothing is built when a level is disabled.
 *
 * Rule (Constitution IV): never log passwords, keys, passphrases, security answers or customer
 * financial details. Log identifiers and outcomes only.
 */
interface AppLogger {
    fun debug(tag: String, message: () -> String)
    fun info(tag: String, message: () -> String)
    fun warn(tag: String, throwable: Throwable? = null, message: () -> String)
    fun error(tag: String, throwable: Throwable? = null, message: () -> String)
}
