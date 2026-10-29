package app.gamenative.service.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpicDeviceActivationTest {

    @Test
    fun `www activation url from EOS is recognised with its user code`() {
        val activation = EpicDeviceActivation.parse("https://www.epicgames.com/activate?userCode=ABCD1234")

        assertNotNull(activation)
        assertEquals("ABCD1234", activation!!.userCode)
        assertEquals("https://www.epicgames.com/activate?userCode=ABCD1234", activation.pageUrl)
    }

    @Test
    fun `bare host, mixed case and trailing slash are recognised`() {
        assertEquals("XY12", EpicDeviceActivation.parse("https://epicgames.com/activate?userCode=XY12")?.userCode)
        assertEquals("XY12", EpicDeviceActivation.parse("HTTPS://WWW.EpicGames.com/Activate/?userCode=XY12")?.userCode)
        assertEquals("XY12", EpicDeviceActivation.parse("  https://www.epicgames.com/id/activate?userCode=XY12\n")?.userCode)
    }

    @Test
    fun `http activation url is upgraded to https`() {
        val activation = EpicDeviceActivation.parse("http://www.epicgames.com/activate?userCode=XY12")

        assertEquals("https://www.epicgames.com/activate?userCode=XY12", activation?.pageUrl)
    }

    @Test
    fun `activation page without user code is still recognised`() {
        val activation = EpicDeviceActivation.parse("https://www.epicgames.com/activate")

        assertNotNull(activation)
        assertNull(activation!!.userCode)
        assertNull(EpicDeviceActivation.parse("https://www.epicgames.com/activate?userCode=%20")!!.userCode)
    }

    @Test
    fun `other epic pages and other hosts are not activation`() {
        assertNull(EpicDeviceActivation.parse("https://www.epicgames.com/id/login?redirectUrl=x"))
        assertNull(EpicDeviceActivation.parse("https://store.epicgames.com/activate?userCode=XY12"))
        assertNull(EpicDeviceActivation.parse("https://www.epicgames.com/deactivate?userCode=XY12"))
        assertNull(EpicDeviceActivation.parse("https://epicgames.com.evil.example/activate?userCode=XY12"))
        assertNull(EpicDeviceActivation.parse("https://evil.example/activate?userCode=XY12"))
        assertNull(EpicDeviceActivation.parse("https://evil.example/?u=https://www.epicgames.com/activate"))
        assertNull(EpicDeviceActivation.parse("file://www.epicgames.com/activate?userCode=XY12"))
    }

    @Test
    fun `malformed input is not activation`() {
        assertNull(EpicDeviceActivation.parse(null))
        assertNull(EpicDeviceActivation.parse(""))
        assertNull(EpicDeviceActivation.parse("   "))
        assertNull(EpicDeviceActivation.parse("not a url"))
        assertNull(EpicDeviceActivation.parse("mailto:someone@epicgames.com"))
        assertNull(EpicDeviceActivation.parse("https://"))
        assertNull(EpicDeviceActivation.parse("www.epicgames.com/activate?userCode=XY12"))
    }
}
