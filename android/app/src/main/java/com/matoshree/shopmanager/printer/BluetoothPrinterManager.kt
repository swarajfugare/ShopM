package com.matoshree.shopmanager.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

data class BluetoothPrinterDevice(
    val name: String,
    val address: String,
    val isSelected: Boolean = false
)

class BluetoothPrinterManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("matoshree_printer_prefs", Context.MODE_PRIVATE)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val KEY_PRINTER_NAME = "selected_printer_name"
        private const val KEY_PRINTER_ADDRESS = "selected_printer_address"
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val CHUNK_SIZE = 256
        private const val CHUNK_DELAY_MS = 25L
    }

    /**
     * Checks whether the app has the necessary Bluetooth permissions.
     */
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks whether Bluetooth hardware is available and switched ON.
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * Returns the currently saved printer name and address if configured.
     */
    fun getSelectedPrinter(): BluetoothPrinterDevice? {
        val address = prefs.getString(KEY_PRINTER_ADDRESS, null) ?: return null
        val name = prefs.getString(KEY_PRINTER_NAME, "Thermal Printer") ?: "Thermal Printer"
        return BluetoothPrinterDevice(name = name, address = address, isSelected = true)
    }

    /**
     * Saves the user-selected printer.
     */
    fun saveSelectedPrinter(device: BluetoothPrinterDevice) {
        prefs.edit()
            .putString(KEY_PRINTER_NAME, device.name)
            .putString(KEY_PRINTER_ADDRESS, device.address)
            .apply()
    }

    /**
     * Clears the selected printer.
     */
    fun forgetPrinter() {
        prefs.edit()
            .remove(KEY_PRINTER_NAME)
            .remove(KEY_PRINTER_ADDRESS)
            .apply()
    }

    /**
     * Retrieves all paired/bonded Bluetooth devices.
     */
    @SuppressLint("MissingPermission")
    fun getPairedPrinters(): List<BluetoothPrinterDevice> {
        if (!hasBluetoothPermission() || !isBluetoothEnabled()) {
            return emptyList()
        }
        val selectedAddress = prefs.getString(KEY_PRINTER_ADDRESS, null)
        val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
        return bondedDevices.map { device ->
            val name = device.name ?: "Unknown Device"
            val address = device.address
            BluetoothPrinterDevice(
                name = name,
                address = address,
                isSelected = address == selectedAddress
            )
        }.sortedBy { it.name }
    }

    /**
     * Connects directly to the selected Bluetooth printer and sends raw ESC/POS byte payload
     * using paced chunking and drain delay to prevent buffer overruns and truncated prints.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendBytes(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val selectedPrinter = getSelectedPrinter()
            ?: return@withContext Result.failure(IllegalStateException("Please select a thermal printer in Settings."))

        if (!hasBluetoothPermission()) {
            return@withContext Result.failure(SecurityException("Bluetooth permission is required to print."))
        }

        val adapter = bluetoothAdapter
            ?: return@withContext Result.failure(IllegalStateException("Bluetooth is not supported on this device."))

        if (!adapter.isEnabled) {
            return@withContext Result.failure(IllegalStateException("Please turn on Bluetooth."))
        }

        var socket: BluetoothSocket? = null
        var outputStream: OutputStream? = null

        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(selectedPrinter.address)
                ?: return@withContext Result.failure(IllegalStateException("Selected printer not found. Check that it is paired."))

            // Cancel active discovery to ensure maximum socket connection speed and stability
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }

            withTimeout(CONNECT_TIMEOUT_MS) {
                // Try standard RFCOMM SPP socket
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket?.connect()
                } catch (e: Exception) {
                    // Fallback to reflection method if standard connection encounters vendor quirks
                    val fallbackMethod = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = fallbackMethod.invoke(device, 1) as BluetoothSocket
                    socket?.connect()
                }
            }

            val activeSocket = socket
                ?: return@withContext Result.failure(IOException("Unable to connect to the printer. Check that it is switched on and paired."))

            outputStream = activeSocket.outputStream

            // Paced transmission to avoid buffer overruns on low-cost thermal printers
            var offset = 0
            while (offset < bytes.size) {
                val length = (bytes.size - offset).coerceAtMost(CHUNK_SIZE)
                outputStream.write(bytes, offset, length)
                outputStream.flush()
                offset += length
                if (offset < bytes.size) {
                    delay(CHUNK_DELAY_MS)
                }
            }

            outputStream.flush()

            // Dynamic drain delay: allow remote thermal printer hardware buffer to finish burning paper
            // and advancing sticker gaps before tearing down RFCOMM socket connection
            val dynamicDrainDelay = (1500L + (bytes.size / 1000) * 450L).coerceIn(1800L, 12000L)
            delay(dynamicDrainDelay)

            Result.success(Unit)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(IOException("Printer connection timed out. Make sure the printer is turned on and in range."))
        } catch (e: IOException) {
            Result.failure(IOException("Unable to connect to printer (${selectedPrinter.name}). Check that it is switched on and within range."))
        } catch (e: Exception) {
            Result.failure(IOException("Printing failed: ${e.localizedMessage ?: "Communication error"}"))
        } finally {
            try {
                outputStream?.close()
            } catch (_: Exception) {}
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }
}
