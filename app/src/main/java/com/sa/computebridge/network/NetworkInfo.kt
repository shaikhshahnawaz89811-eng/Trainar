package com.sa.computebridge.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfo {
    fun ipv4Addresses(): List<String> = buildList {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            if (!ni.isUp || ni.isLoopback) return@forEach
            ni.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress) add(address.hostAddress ?: "")
            }
        }
    }.filter { it.isNotBlank() }.distinct()

    fun primaryIpv4(): String? = ipv4Addresses().firstOrNull()

    /** Prefer private LAN addresses used by Wi-Fi/hotspot over VPN/mobile routes. */
    fun preferredIpv4(): String? = ipv4Addresses().sortedWith(compareByDescending<String> { isPrivateLan(it) }.thenBy { it }).firstOrNull()

    private fun isPrivateLan(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }
}
