package app.twilmo

import app.twilmo.domain.telUriNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelUrisTest {
    @Test
    fun e164PassesThrough() {
        assertEquals("+15550100", telUriNumber("tel:+15550100"))
    }

    @Test
    fun percentEncodedPlusIsDecoded() {
        assertEquals("+15550100", telUriNumber("tel:%2B15550100"))
        assertEquals("+15550100", telUriNumber("tel:%2b15550100"))
    }

    @Test
    fun fullyPercentEncodedTargetIsDecoded() {
        assertEquals("+15550100", telUriNumber("tel:%2B1%20(555)%200100"))
        assertEquals("+15550100", telUriNumber("tel:%2B1%20%28555%29%200100"))
    }

    @Test
    fun malformedPercentEscapesAreRejected() {
        assertNull(telUriNumber("tel:+1555010%2"))
        assertNull(telUriNumber("tel:+1555%GZ0100"))
    }

    @Test
    fun nationalFormatSeparatorsAreDropped() {
        assertEquals("0412345678", telUriNumber("tel:0412 345 678"))
        assertEquals("5550100", telUriNumber("tel:555-01.00"))
        assertEquals("+15550100", telUriNumber("tel:+1 (555) 0100"))
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals("+15550100", telUriNumber("TEL:+15550100"))
    }

    @Test
    fun rfc3966ParametersAreIgnored() {
        assertEquals("+15550100", telUriNumber("tel:+15550100;isub=12"))
    }

    @Test
    fun nonTelSchemeIsRejected() {
        assertNull(telUriNumber("sip:+15550100"))
        assertNull(telUriNumber("+15550100"))
    }

    @Test
    fun emptyAndPlusOnlyAreRejected() {
        assertNull(telUriNumber("tel:"))
        assertNull(telUriNumber("tel:+"))
    }

    @Test
    fun lettersAndMisplacedPlusAreRejected() {
        assertNull(telUriNumber("tel:1-800-FLOWERS"))
        assertNull(telUriNumber("tel:55+50100"))
    }
}
