package app.ezmanga.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.ezmanga.core.EzMangaDiscovery
import app.ezmanga.core.PairingPayload

/**
 * Advertises the TV's HTTP port over mDNS so the phone can find it after the
 * user types the six-digit pairing code.
 */
class TvDiscoveryAdvertiser(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(serviceName: String, port: Int) {
        stop()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = EzMangaDiscovery.SERVICE_TYPE
            this.port = port
            setAttribute(EzMangaDiscovery.VERSION_ATTRIBUTE, PairingPayload.PROTOCOL_VERSION.toString())
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS service registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: $errorCode")
            }
        }

        listener = registrationListener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure {
            Log.w(TAG, "Could not start mDNS discovery", it)
            listener = null
        }
    }

    fun stop() {
        listener?.let { active ->
            runCatching { nsdManager.unregisterService(active) }
        }
        listener = null
    }

    private companion object {
        const val TAG = "EZMangaTv"
    }
}
