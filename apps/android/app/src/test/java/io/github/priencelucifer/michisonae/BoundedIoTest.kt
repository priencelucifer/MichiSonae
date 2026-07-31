package io.github.priencelucifer.michisonae

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedIoTest {
    @Test
    fun boundedReaderAcceptsLimitAndRejectsTheNextByte() {
        assertEquals(
            "abcd",
            ByteArrayInputStream("abcd".toByteArray()).use { it.readUtf8AtMost(4) },
        )
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream("abcde".toByteArray()).use { it.readUtf8AtMost(4) }
        }
    }
}
