package ru.fhnb16.btmanage.data

import java.util.UUID

/**
 * Names of GATT services, characteristics and descriptors, for the developer console, and
 * the reading of the values whose layout the Bluetooth SIG defines. The rest is shown as
 * bytes, with the text they spell where they spell one.
 */
object GattNames {
    private const val BASE_TAIL = "-0000-1000-8000-00805f9b34fb"

    private val short = mapOf(
        // Services.
        0x1800 to "Generic Access",
        0x1801 to "Generic Attribute",
        0x1802 to "Immediate Alert",
        0x1803 to "Link Loss",
        0x1804 to "Tx Power",
        0x1805 to "Current Time",
        0x1808 to "Glucose",
        0x1809 to "Health Thermometer",
        0x180A to "Device Information",
        0x180D to "Heart Rate",
        0x180F to "Battery",
        0x1810 to "Blood Pressure",
        0x1812 to "Human Interface Device",
        0x1814 to "Running Speed and Cadence",
        0x1816 to "Cycling Speed and Cadence",
        0x1818 to "Cycling Power",
        0x1819 to "Location and Navigation",
        0x181A to "Environmental Sensing",
        0x181C to "User Data",
        0x181D to "Weight Scale",
        0x1822 to "Pulse Oximeter",
        0x1826 to "Fitness Machine",
        0x1844 to "Volume Control",
        0x1846 to "Coordinated Set Identification",
        0x184E to "Audio Stream Control",
        0x184F to "Broadcast Audio Scan",
        0x1850 to "Published Audio Capabilities",
        0x1853 to "Common Audio",
        0x1855 to "Telephony and Media Audio",
        0xFE2C to "Google Fast Pair",
        0xFEE0 to "Xiaomi / Huami",
        0xFEE7 to "Tencent",
        0xFD5A to "Samsung",
        // Characteristics.
        0x2A00 to "Device Name",
        0x2A01 to "Appearance",
        0x2A04 to "Preferred Connection Parameters",
        0x2A05 to "Service Changed",
        0x2A06 to "Alert Level",
        0x2A07 to "Tx Power Level",
        0x2A19 to "Battery Level",
        0x2A1A to "Battery Power State",
        0x2A22 to "Boot Keyboard Input Report",
        0x2A23 to "System ID",
        0x2A24 to "Model Number",
        0x2A25 to "Serial Number",
        0x2A26 to "Firmware Revision",
        0x2A27 to "Hardware Revision",
        0x2A28 to "Software Revision",
        0x2A29 to "Manufacturer Name",
        0x2A2A to "IEEE Regulatory Certification",
        0x2A2B to "Current Time",
        0x2A32 to "Boot Keyboard Output Report",
        0x2A33 to "Boot Mouse Input Report",
        0x2A35 to "Blood Pressure Measurement",
        0x2A37 to "Heart Rate Measurement",
        0x2A38 to "Body Sensor Location",
        0x2A39 to "Heart Rate Control Point",
        0x2A4A to "HID Information",
        0x2A4B to "Report Map",
        0x2A4C to "HID Control Point",
        0x2A4D to "Report",
        0x2A4E to "Protocol Mode",
        0x2A50 to "PnP ID",
        0x2A5F to "PLX Continuous Measurement",
        0x2A6E to "Temperature",
        0x2A6F to "Humidity",
        0x2A9D to "Weight Measurement",
        0x2AA6 to "Central Address Resolution",
        0x2B29 to "Client Supported Features",
        0x2B2A to "Database Hash",
        0x2B3A to "Server Supported Features",
        // Descriptors.
        0x2900 to "Extended Properties",
        0x2901 to "User Description",
        0x2902 to "Client Configuration",
        0x2903 to "Server Configuration",
        0x2904 to "Presentation Format",
        0x2908 to "Report Reference",
    )

    private val long = mapOf(
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e" to "Nordic UART",
        "6e400002-b5a3-f393-e0a9-e50e24dcca9e" to "Nordic UART: to the device",
        "6e400003-b5a3-f393-e0a9-e50e24dcca9e" to "Nordic UART: from the device",
        "00000006-0000-3512-2118-0009af100700" to "Huami battery",
        "fe2c1233-8366-4814-8eb0-01de32100bea" to "Fast Pair: model ID",
        "fe2c1234-8366-4814-8eb0-01de32100bea" to "Fast Pair: key-based pairing",
        "fe2c1235-8366-4814-8eb0-01de32100bea" to "Fast Pair: passkey",
        "fe2c1236-8366-4814-8eb0-01de32100bea" to "Fast Pair: account key",
        "fe2c1237-8366-4814-8eb0-01de32100bea" to "Fast Pair: additional data",
        "74ec2172-0bad-4d01-8f77-997b2be0722a" to "Apple accessory protocol",
        "df21fe2c-2515-4fdb-8886-f12c4d67927c" to "Fast Pair message stream",
    )

    /** The 16-bit number of a UUID on the Bluetooth base, or null. */
    fun shortOf(uuid: UUID): Int? {
        val text = uuid.toString()
        if (!text.startsWith("0000") || !text.endsWith(BASE_TAIL)) return null
        return text.substring(4, 8).toIntOrNull(16)
    }

    /** "2A19 Battery Level", "6e400002-… Nordic UART: to the device", or the UUID alone. */
    fun describe(uuid: UUID): String {
        val number = shortOf(uuid)
        val code = number?.let { "%04X".format(it) } ?: uuid.toString()
        val name = (number?.let { short[it] }) ?: long[uuid.toString()]
        return if (name != null) "$code $name" else code
    }

    /** Characteristics whose value is UTF-8 text. */
    private val textual = setOf(0x2A00, 0x2A24, 0x2A25, 0x2A26, 0x2A27, 0x2A28, 0x2A29, 0x2901)

    /** What a value means, where its layout is defined; null where it is not. */
    fun decode(uuid: UUID, value: ByteArray): String? {
        val number = shortOf(uuid) ?: return null
        fun u8(at: Int) = value.getOrNull(at)?.toInt()?.and(0xFF)
        fun u16(at: Int): Int? {
            val low = u8(at) ?: return null
            val high = u8(at + 1) ?: return null
            return low or (high shl 8)
        }
        return when (number) {
            in textual -> value.toString(Charsets.UTF_8).trimEnd('\u0000')
            0x2A19 -> u8(0)?.let { "$it %" }
            0x2A01 -> u16(0)?.let { "appearance 0x%04X".format(it) }
            0x2A07 -> value.getOrNull(0)?.let { "$it dBm" }
            0x2A50 -> {
                val source = when (u8(0)) {
                    1 -> "Bluetooth SIG"
                    2 -> "USB"
                    else -> "source ${u8(0)}"
                }
                val vendor = u16(1)
                val product = u16(3)
                val version = u16(5)
                if (vendor == null || product == null) null else "$source, vendor 0x%04X, product 0x%04X, version 0x%04X".format(vendor, product, version ?: 0)
            }
            0x2A37 -> {
                val flags = u8(0) ?: return null
                val rate = if (flags and 1 == 0) u8(1) else u16(1)
                rate?.let { "$it bpm" }
            }
            0x2A04 -> {
                val min = u16(0)
                val max = u16(2)
                val latency = u16(4)
                val timeout = u16(6)
                if (min == null || max == null) null else "interval ${min * 1.25}–${max * 1.25} ms, latency $latency, timeout ${(timeout ?: 0) * 10} ms"
            }
            0x2902 -> u16(0)?.let {
                when (it) {
                    0 -> "off"
                    1 -> "notifications on"
                    2 -> "indications on"
                    else -> "0x%04X".format(it)
                }
            }
            0x2908 -> if (value.size >= 2) "report ${u8(0)}, " + when (u8(1)) {
                1 -> "input"
                2 -> "output"
                3 -> "feature"
                else -> "type ${u8(1)}"
            } else {
                null
            }
            else -> null
        }
    }
}
