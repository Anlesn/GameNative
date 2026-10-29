package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultScreenSizeTest {

    @Test
    fun `4 by 3 displays get 4 by 3`() {
        assertEquals("1280x960", defaultScreenSizeFor(2048, 1536))
        assertEquals("1280x960", defaultScreenSizeFor(1024, 768))
    }

    @Test
    fun `square tablet 1_41 gets 16 by 9 1080p`() {
        // OnePlus Pad 4
        assertEquals("1920x1080", defaultScreenSizeFor(3392, 2400))
    }

    @Test
    fun `low resolution square tablet gets 16 by 9 720p`() {
        assertEquals("1280x720", defaultScreenSizeFor(1414, 1000))
    }

    @Test
    fun `3 by 2 displays get 16 by 9`() {
        assertEquals("1280x720", defaultScreenSizeFor(2160, 1440))
        assertEquals("1920x1080", defaultScreenSizeFor(2400, 1600))
    }

    @Test
    fun `16 by 10 displays get 16 by 10`() {
        assertEquals("1280x800", defaultScreenSizeFor(2560, 1600))
        assertEquals("1280x800", defaultScreenSizeFor(1280, 800))
    }

    @Test
    fun `16 by 9 displays get 16 by 9`() {
        assertEquals("1280x720", defaultScreenSizeFor(1920, 1080))
        assertEquals("1280x720", defaultScreenSizeFor(2560, 1440))
        assertEquals("1920x1080", defaultScreenSizeFor(3840, 2160))
    }

    @Test
    fun `wide phones 2_2 get 16 by 9`() {
        assertEquals("1280x720", defaultScreenSizeFor(2376, 1080))
        assertEquals("1280x720", defaultScreenSizeFor(3168, 1440))
    }

    @Test
    fun `16 by 10 band edges`() {
        assertEquals("1280x720", defaultScreenSizeFor(1540, 1000))
        assertEquals("1280x800", defaultScreenSizeFor(1560, 1000))
        assertEquals("1280x800", defaultScreenSizeFor(1680, 1000))
        assertEquals("1280x720", defaultScreenSizeFor(1700, 1000))
    }

    @Test
    fun `portrait input gives the same result as landscape`() {
        assertEquals("1280x960", defaultScreenSizeFor(1536, 2048))
        assertEquals("1920x1080", defaultScreenSizeFor(2400, 3392))
        assertEquals("1280x800", defaultScreenSizeFor(1600, 2560))
        assertEquals("1920x1080", defaultScreenSizeFor(2160, 3840))
        assertEquals("1280x720", defaultScreenSizeFor(1080, 2376))
    }

    @Test
    fun `invalid sizes fall back to 16 by 9 720p`() {
        assertEquals("1280x720", defaultScreenSizeFor(0, 0))
        assertEquals("1280x720", defaultScreenSizeFor(1920, 0))
        assertEquals("1280x720", defaultScreenSizeFor(-1, 1080))
    }
}
