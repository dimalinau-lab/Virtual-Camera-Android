package com.example.ccamera

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ExampleUnitTest {
    private fun computeOrientationWithCone(x: Float, y: Float, z: Float): Int? {
        val magnitude = x * x + y * y
        val absZ = abs(z)
        if (magnitude < 25.0f || sqrt(magnitude.toDouble()) < absZ * 0.85) {
            return null
        }
        val angleRad = atan2(-y.toDouble(), x.toDouble())
        var orientation = 90 - (angleRad * (180.0 / Math.PI)).roundToInt()
        while (orientation >= 360) orientation -= 360
        while (orientation < 0) orientation += 360
        return ((orientation + 45) / 90 * 90) % 360
    }

    @Test
    fun pairingManager_ephemeralTokenLifecycle() {
        val tok = "ephemeral-test-token-1"
        PairingManager.addEphemeralToken(tok, durationMs = 60_000L)
        assertTrue(PairingManager.isEphemeralTokenValid(tok))
        assertFalse(PairingManager.isEphemeralTokenValid("invalid-token"))
        PairingManager.ephemeralTokens.remove(tok)
        assertFalse(PairingManager.isEphemeralTokenValid(tok))

        PairingManager.addEphemeralToken("expired-tok", durationMs = -10L)
        assertFalse(PairingManager.isEphemeralTokenValid("expired-tok"))
    }

    @Test
    fun orientationCone_blocksDownwardShootingAndDetectsNormalAngles() {
        // Телефон направлен строго вниз (90° под себя) с небольшим креном вбок: должен блокироваться
        assertNull(computeOrientationWithCone(x = 2.0f, y = 1.0f, z = 9.5f))
        assertNull(computeOrientationWithCone(x = -3.0f, y = 0.5f, z = -9.2f))

        // Обычный вертикальный портрет (0°, gravity +Y -> y = -9.8)
        assertEquals(0, computeOrientationWithCone(x = 0.0f, y = -9.8f, z = 0.5f))
        // Альбом 90° (gravity -X -> x = +9.8)
        assertEquals(90, computeOrientationWithCone(x = 9.8f, y = 0.0f, z = 0.5f))
        // Альбом 270° (gravity +X -> x = -9.8)
        assertEquals(270, computeOrientationWithCone(x = -9.8f, y = 0.0f, z = 0.5f))
    }
}
