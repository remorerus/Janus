package com.ymid.wakeonlan.monitoring

import com.ymid.wakeonlan.persistence.models.Device
import com.ymid.wakeonlan.network.DeviceNetworkProfiles
import com.ymid.wakeonlan.ping.Ping
import java.net.InetAddress

/** Blocking reachability check shared by the background workers. */
object HostReachability {

    private const val PING_TIMEOUT_MS = 2000
    private const val PING_ATTEMPTS = 2

    /** The address the list pings for this device, or null when it has none. */
    fun statusHost(device: Device): String? =
        DeviceNetworkProfiles.statusAddresses(device).firstOrNull()
            ?: device.sshAddress?.takeIf { it.isNotBlank() }

    /** A device is online when any saved profile IP responds; legacy SSH fallback remains supported. */
    fun isDeviceReachable(device: Device): Boolean {
        val hosts = DeviceNetworkProfiles.statusAddresses(device).ifEmpty {
            listOfNotNull(device.sshAddress?.takeIf { it.isNotBlank() })
        }
        return hosts.any { isReachable(it) }
    }

    fun isReachable(host: String): Boolean {
        val address = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return false
        }
        return (1..PING_ATTEMPTS).any { icmpPing(address) ?: fallbackReachable(address) }
    }

    private fun icmpPing(address: InetAddress): Boolean? {
        var result: Boolean? = null
        Ping(address, object : Ping.PingListener {
            override fun onPing(timeMs: Long) {
                result = timeMs != Ping.TIMED_OUT_MS
            }

            override fun onPingException(e: Exception) {
                result = null
            }
        }).apply { timeoutMs = PING_TIMEOUT_MS }.run()
        return result
    }

    private fun fallbackReachable(address: InetAddress): Boolean = try {
        address.isReachable(PING_TIMEOUT_MS)
    } catch (_: Exception) {
        false
    }
}
