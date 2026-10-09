package dev.cartridge.domain

enum class SessionState { Idle, Preparing, Running, Paused, Stopping, Error }

/** A single owner must serialize access; illegal transitions indicate a programming error. */
class SessionMachine {
    var state = SessionState.Idle
        private set

    fun transition(next: SessionState) {
        val allowed = when (state) {
            SessionState.Idle -> setOf(SessionState.Preparing)
            SessionState.Preparing -> setOf(SessionState.Running, SessionState.Stopping, SessionState.Error)
            SessionState.Running -> setOf(SessionState.Paused, SessionState.Stopping, SessionState.Error)
            SessionState.Paused -> setOf(SessionState.Running, SessionState.Stopping, SessionState.Error)
            SessionState.Stopping -> setOf(SessionState.Idle, SessionState.Error)
            SessionState.Error -> setOf(SessionState.Stopping, SessionState.Idle)
        }
        check(next in allowed) { "Invalid emulation transition: $state -> $next" }
        state = next
    }
}
