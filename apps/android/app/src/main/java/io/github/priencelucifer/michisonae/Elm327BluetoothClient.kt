package io.github.priencelucifer.michisonae

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.net.SocketTimeoutException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class Elm327BluetoothClient private constructor(
    private val socket: BluetoothSocket,
) : Closeable {
    private val closed = AtomicBoolean()

    @Synchronized
    fun query(command: Elm327Command): String {
        require(command.isAllowedReadOnlyCommand()) { "Only read-only ELM327 commands are allowed" }
        check(!closed.get()) { "OBD socket is closed" }
        val timedOut = AtomicBoolean()
        val timeout = object : TimerTask() {
            override fun run() {
                timedOut.set(true)
                close()
            }
        }
        TIMEOUT_TIMER.schedule(timeout, QUERY_TIMEOUT_MS)

        try {
            socket.outputStream.write(
                "${command.wireValue}\r".toByteArray(StandardCharsets.US_ASCII),
            )
            socket.outputStream.flush()

            val response = StringBuilder()
            while (true) {
                val next = socket.inputStream.read()
                if (next < 0) throw EOFException("ELM327 response ended before its prompt")
                if (next.toChar() == '>') return response.toString()
                if (response.length == MAX_RESPONSE_CHARS) {
                    throw IOException("ELM327 response exceeded $MAX_RESPONSE_CHARS characters")
                }
                response.append(next.toChar())
            }
        } catch (failure: IOException) {
            close()
            if (timedOut.get()) {
                throw SocketTimeoutException("ELM327 response timed out").apply {
                    initCause(failure)
                }
            }
            throw failure
        } finally {
            timeout.cancel()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching(socket::close)
        }
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 8_192
        private const val QUERY_TIMEOUT_MS = 5_000L
        private val TIMEOUT_TIMER = Timer("elm327-timeouts", true)
        private val SERIAL_PORT_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice): Elm327BluetoothClient {
            val socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
            return try {
                socket.connect()
                Elm327BluetoothClient(socket)
            } catch (failure: Exception) {
                runCatching(socket::close)
                throw failure
            }
        }
    }
}
