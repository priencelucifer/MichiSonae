package io.github.priencelucifer.michisonae

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.UUID

internal class Elm327BluetoothClient private constructor(
    private val socket: BluetoothSocket,
) : Closeable {
    fun query(command: Elm327Command): String {
        require(command.isAllowedReadOnlyCommand()) { "Only read-only ELM327 commands are allowed" }
        socket.outputStream.write(
            "${command.wireValue}\r".toByteArray(StandardCharsets.US_ASCII),
        )
        socket.outputStream.flush()

        val response = StringBuilder()
        while (response.length < MAX_RESPONSE_CHARS) {
            val next = socket.inputStream.read()
            if (next < 0 || next.toChar() == '>') break
            response.append(next.toChar())
        }
        return response.toString()
    }

    override fun close() = socket.close()

    companion object {
        private const val MAX_RESPONSE_CHARS = 8_192
        private val SERIAL_PORT_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice): Elm327BluetoothClient {
            val socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID)
            return try {
                socket.connect()
                Elm327BluetoothClient(socket)
            } catch (failure: Exception) {
                socket.close()
                throw failure
            }
        }
    }
}
