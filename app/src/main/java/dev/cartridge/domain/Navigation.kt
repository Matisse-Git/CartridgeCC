package dev.cartridge.domain

import kotlin.math.abs

data class Selection(val index: Int = 0, val count: Int = 3) {
    init { require(count > 0 && index in 0 until count) }
    fun move(delta: Int) = copy(index = (index + delta).coerceIn(0, count - 1))
}

/** Poll every UI frame. Repeats even if a held Android axis sends no further events. */
class AxisRepeater(private val deadZone: Float = 0.35f) {
    private var direction = 0
    private var nextRepeat = 0L
    fun poll(value: Float, nowMillis: Long): Int {
        val next = if (abs(value) < deadZone) 0 else if (value > 0) 1 else -1
        if (next == 0) { reset(); return 0 }
        if (next != direction) {
            direction = next
            nextRepeat = nowMillis + 300
            return next
        }
        if (nowMillis < nextRepeat) return 0
        nextRepeat = nowMillis + 110
        return direction
    }
    fun reset() { direction = 0; nextRepeat = 0 }
}

/** Critically damped retargetable motion: no queue, no overshoot after rapid navigation. */
class FocusSpring(var position: Float = 0f) {
    private var velocity = 0f
    fun step(target: Float, deltaSeconds: Float): Float {
        val dt = deltaSeconds.coerceIn(0f, 0.05f)
        val omega = 14f
        val offset = position - target
        val decay = kotlin.math.exp(-omega * dt)
        val carry = velocity + omega * offset
        position = target + (offset + carry * dt) * decay
        velocity = (velocity - omega * carry * dt) * decay
        return position
    }
}

