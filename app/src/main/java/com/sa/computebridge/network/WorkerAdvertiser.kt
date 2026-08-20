package com.sa.computebridge.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class WorkerAdvertiser(context: Context, private val portProvider: () -> Int) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start(workerId: String) {
        if (registrationListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "ComputeBridge-$workerId"
            serviceType = "_sa-compute._tcp"
            port = portProvider()
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { registrationListener = null }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { registrationListener = null }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        registrationListener = null
    }
}
