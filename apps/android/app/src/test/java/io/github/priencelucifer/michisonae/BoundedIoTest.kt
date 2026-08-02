package io.github.priencelucifer.michisonae

import java.io.ByteArrayInputStream
import java.io.InputStream
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

    @Test
    fun byteLimitCannotBeBypassedWithMultibyteTextOrTinyChunks() {
        val utf8 = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(utf8).use { it.readUtf8AtMost(1) }
        }

        val input = object : InputStream() {
            private val bytes = "chunked".toByteArray()
            private var index = 0

            override fun read(): Int = if (index == bytes.size) -1 else bytes[index++].toInt()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (index == bytes.size) return -1
                buffer[offset] = bytes[index++]
                return 1
            }
        }

        assertEquals("chunked", input.use { it.readUtf8AtMost(7) })
    }

    @Test
    fun invalidLimitIsRejectedBeforeReadingTheStream() {
        var read = false
        val input = object : InputStream() {
            override fun read(): Int {
                read = true
                return -1
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            input.readUtf8AtMost(0)
        }
        assertEquals(false, read)
    }
}
