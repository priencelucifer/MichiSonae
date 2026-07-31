package io.github.priencelucifer.michisonae

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readUtf8AtMost(maximumBytes: Int): String {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, 8_192))
    val buffer = ByteArray(8_192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "Response exceeds its size limit" }
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
