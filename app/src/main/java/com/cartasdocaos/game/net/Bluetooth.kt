package com.cartasdocaos.game.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import java.io.IOException
import java.util.UUID

object Bt {
    val SERVICE_UUID: UUID = UUID.fromString("6f1d2a40-5c1e-4b7a-9d3a-2b9c7e1f4a10")
    const val SERVICE_NAME = "CartasDoCaos"
}

/** Servidor Bluetooth (RFCOMM) do anfitrião. As permissões são pedidas pela interface. */
class BtServer(
    private val adapter: BluetoothAdapter,
    private val onConnection: (LineConnection) -> Unit
) : Acceptor {
    private var server: BluetoothServerSocket? = null
    @Volatile private var stopped = false

    /** Lança IOException/SecurityException se o Bluetooth não puder ser usado. */
    @SuppressLint("MissingPermission")
    fun start() {
        val ss = adapter.listenUsingRfcommWithServiceRecord(Bt.SERVICE_NAME, Bt.SERVICE_UUID)
        server = ss
        val t = Thread({
            while (!stopped) {
                val sock = try { ss.accept() } catch (_: IOException) { break }
                try {
                    onConnection(LineConnection(sock.inputStream, sock.outputStream) {
                        try { sock.close() } catch (_: IOException) { }
                    })
                } catch (_: Exception) {
                    try { sock.close() } catch (_: IOException) { }
                }
            }
        }, "bt-accept")
        t.isDaemon = true
        t.start()
    }

    override fun stop() {
        stopped = true
        try { server?.close() } catch (_: IOException) { }
    }
}

object BtClient {
    /** Conecta ao anfitrião (bloqueante: chame fora da thread principal). */
    @SuppressLint("MissingPermission")
    fun connect(adapter: BluetoothAdapter, device: BluetoothDevice): LineConnection {
        adapter.cancelDiscovery() // descoberta ativa atrapalha a conexão
        val sock = device.createRfcommSocketToServiceRecord(Bt.SERVICE_UUID)
        try {
            sock.connect()
        } catch (e: IOException) {
            try { sock.close() } catch (_: IOException) { }
            throw e
        }
        return LineConnection(sock.inputStream, sock.outputStream) {
            try { sock.close() } catch (_: IOException) { }
        }
    }
}
