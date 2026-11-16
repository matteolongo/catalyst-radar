package com.catalystradar.domain.catalyst

/** Whether a state transition moved a company to a higher or lower catalyst band. */
enum class TransitionDirection {
    UPGRADE,
    DOWNGRADE,
    ;

    companion object {
        /** Derived from the declared band order of [CatalystState]; equal states are not a transition. */
        fun of(from: CatalystState, to: CatalystState): TransitionDirection {
            require(from != to) { "a transition needs two different states, was $from" }
            return if (to.ordinal > from.ordinal) UPGRADE else DOWNGRADE
        }
    }
}
