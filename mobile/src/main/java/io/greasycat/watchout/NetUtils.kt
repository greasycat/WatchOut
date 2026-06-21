package io.greasycat.watchout

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    /** Non-loopback IPv4 addresses of this device (LAN + Tailscale, etc.). */
    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    } catch (e: Exception) {
        emptyList()
    }
}
