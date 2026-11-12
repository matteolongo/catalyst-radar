package com.catalystradar.common

/**
 * Caller-supplied input failed validation. The API maps only this type
 * to 400; any other IllegalArgumentException is an internal invariant
 * failure and becomes a logged 500. It extends IllegalArgumentException
 * so `require`-style callers and tests keep their existing contract.
 */
class InvalidInputException(message: String) : IllegalArgumentException(message)

inline fun requireValidInput(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidInputException(message())
}
