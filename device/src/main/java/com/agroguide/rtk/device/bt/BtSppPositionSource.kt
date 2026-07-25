package com.agroguide.rtk.device.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import com.agroguide.rtk.device.PositionSource
import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.device.SourceEvent.ConnectionState.State
import com.agroguide.rtk.nmea.NmeaLineAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Owns the RFCOMM socket to the RTK receiver. Emits NMEA lines; exposes
 * [write] so the NTRIP client (phase 2) can push RTCM3 down the same link.
 * Watchdog: if no NMEA for [watchdogMs], the socket is torn down and the
 * reconnect loop takes over with exponential backoff.
 */
@SuppressLint("MissingPermission")  // caller must hold BLUETOOTH_CONNECT
class BtSppPositionSource(
    private val deviceAddress: String,
    private val watchdogMs: Long = 5000,
) : PositionSource {
    override val name = "BT-SPP"
    @Volatile private var output: OutputStream? = null
    private val policy = ReconnectPolicy()

    /** Write RTCM/UBX bytes to the receiver; no-op if disconnected. */
    fun write(bytes: ByteArray) {
        try { output?.write(bytes) } catch (_: IOException) { /* reconnect loop handles it */ }
    }

    override val events: Flow<SourceEvent> = channelFlow {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            send(SourceEvent.ConnectionState(State.ERROR, "no_bluetooth"))
            return@channelFlow
        }
        while (isActive) {
            send(SourceEvent.ConnectionState(State.CONNECTING))
            var socket: BluetoothSocket? = null
            try {
                val device = adapter.getRemoteDevice(deviceAddress)
                // Optimization only, and it needs BLUETOOTH_SCAN on API 31+:
                // never let a missing permission take down the session.
                try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                socket = connectSocket(device)
                output = socket.outputStream
                send(SourceEvent.ConnectionState(State.CONNECTED, deviceLabel()))
                policy.reset()
                readUntilStale(socket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IOException (peer refused/off), SecurityException (permission
                // revoked), IllegalArgumentException (bad MAC)... a failed
                // connection must surface in the UI, never crash the app.
                send(SourceEvent.ConnectionState(State.ERROR, e.message ?: e.javaClass.simpleName))
            } finally {
                output = null
                try { socket?.close() } catch (_: Exception) {}
            }
            if (!isActive) break
            send(SourceEvent.ConnectionState(State.DISCONNECTED))
            delay(policy.nextDelayMs())
        }
    }

    private fun deviceLabel(): String = try {
        BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(deviceAddress)?.name ?: deviceAddress
    } catch (_: Exception) {
        deviceAddress
    }

    /**
     * Opens the RFCOMM channel. The standard SDP lookup fails on some SPP
     * modules and on a few vendor stacks; the reflective channel-1 fallback is
     * the well-known workaround, tried only after the normal path fails.
     */
    private fun connectSocket(device: BluetoothDevice): BluetoothSocket {
        val primary = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            primary.connect()
            return primary
        } catch (e: IOException) {
            try { primary.close() } catch (_: IOException) {}
            val fallback = try {
                @Suppress("UNCHECKED_CAST")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            } catch (_: Exception) {
                throw e   // no fallback available: report the original failure
            }
            fallback.connect()
            return fallback
        }
    }

    /** Blocking read loop on IO dispatcher; returns when watchdog fires or stream ends. */
    private suspend fun ProducerScope<SourceEvent>.readUntilStale(socket: BluetoothSocket) =
        withContext(Dispatchers.IO) {
            val assembler = NmeaLineAssembler()
            val ackScanner = com.agroguide.rtk.nmea.ubx.UbxAckScanner()
            val buf = ByteArray(1024)
            var lastNmeaMs = SystemClock.elapsedRealtime()
            val input = socket.inputStream
            val watchdog = launch {
                while (isActive) {
                    delay(1000)
                    if (SystemClock.elapsedRealtime() - lastNmeaMs > watchdogMs) {
                        try { socket.close() } catch (_: IOException) {}  // unblocks read()
                        break
                    }
                }
            }
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val now = SystemClock.elapsedRealtime()
                    val chunk = buf.copyOf(n)
                    for (line in assembler.feed(chunk)) {
                        lastNmeaMs = now
                        send(SourceEvent.Nmea(line, now))
                    }
                    for (ack in ackScanner.feed(chunk)) {
                        send(SourceEvent.UbxAckEvent(ack.ok, ack.ackedCls, ack.ackedId))
                    }
                }
            } catch (_: IOException) { /* socket closed by watchdog or peer */ }
            finally { watchdog.cancel() }
        }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
