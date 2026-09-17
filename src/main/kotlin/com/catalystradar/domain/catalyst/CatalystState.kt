package com.catalystradar.domain.catalyst

/**
 * Point-in-time catalyst state of a company.
 *
 * States are derived from the deterministic catalyst score by the
 * scoring service; nothing else may assign them.
 */
enum class CatalystState {
    NORMAL,
    WATCH,
    BUILDING,
    CATALYZED,
    HIGH,
}
