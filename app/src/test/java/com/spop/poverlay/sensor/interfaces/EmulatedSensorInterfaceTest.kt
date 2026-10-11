package com.spop.poverlay.sensor.interfaces

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatedSensorInterfaceTest {
    @Test
    fun `re-emits unchanged values every sample like the hardware`() = runBlocking {
        val sensor = EmulatedSensorInterface(EmulatedModel.Bike, samplePeriodMs = 10)
        try {
            // Resistance is constant in emulation; a deduplicating flow would emit it once.
            val samples = withTimeout(2000) { sensor.resistance.take(5).toList() }
            assertEquals(List(5) { 40f }, samples)
        } finally {
            sensor.stop()
        }
    }

    @Test
    fun `bike derives speed from power and reports no incline`() = runBlocking {
        val sensor = EmulatedSensorInterface(EmulatedModel.Bike, samplePeriodMs = 10)
        try {
            withTimeout(2000) {
                assertTrue(sensor.speed.first() > 0f)
                assertEquals(0f, sensor.incline.first())
            }
        } finally {
            sensor.stop()
        }
    }

    @Test
    fun `tread reports speed and incline directly`() = runBlocking {
        val sensor = EmulatedSensorInterface(EmulatedModel.Tread, samplePeriodMs = 10)
        try {
            withTimeout(2000) {
                assertEquals(6.2f, sensor.speed.first(), 0.05f)
                assertEquals(3f, sensor.incline.first())
                assertEquals(0f, sensor.cadence.first())
            }
        } finally {
            sensor.stop()
        }
    }

    @Test
    fun `hardware sample periods`() {
        assertEquals(200L, EmulatedModel.BikePlus.defaultSamplePeriodMs)
        assertEquals(200L, EmulatedModel.CrossTrainer.defaultSamplePeriodMs)
    }
}
