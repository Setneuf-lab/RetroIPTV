package com.ale.cineiptv.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS de la app (Ajustes → DNS). Algunas operadoras bloquean servidores IPTV haciendo que
 * su dominio apunte a 127.0.0.1; con un DNS seguro (DNS sobre HTTPS) la operadora no puede
 * cambiar la respuesta.
 *  - auto: el de la operadora y, si está bloqueado o falla, Cloudflare/Google
 *  - system: solo el de la operadora
 *  - cloudflare / google / quad9: siempre ese
 */
object SmartDns : Dns {

    @Volatile var mode: String = "auto"

    val MODES = listOf("auto", "system", "cloudflare", "google", "quad9")

    private val bootstrap by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun doh(url: String, vararg ips: String): DnsOverHttps =
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url(url.toHttpUrl())
            // Las direcciones del propio DNS van fijas: no hay que preguntar a la operadora por ellas
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .includeIPv6(false)
            .build()

    private val cloudflare by lazy { doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1") }
    private val google by lazy { doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4") }
    private val quad9 by lazy { doh("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112") }

    override fun lookup(hostname: String): List<InetAddress> {
        // Direcciones IP o nombres locales: nada que resolver fuera
        if (hostname == "localhost" || hostname.all { it.isDigit() || it == '.' } || ':' in hostname) {
            return Dns.SYSTEM.lookup(hostname)
        }
        return when (mode) {
            "system" -> Dns.SYSTEM.lookup(hostname)
            "cloudflare" -> secure(hostname, cloudflare, google)
            "google" -> secure(hostname, google, cloudflare)
            "quad9" -> secure(hostname, quad9, cloudflare)
            else -> auto(hostname)
        }
    }

    /** DNS elegido; si no responde, el otro seguro; y como último recurso el de la operadora. */
    private fun secure(host: String, first: Dns, second: Dns): List<InetAddress> {
        for (d in listOf(first, second)) {
            runCatching { d.lookup(host) }.getOrNull()?.takeIf { usable(it) }?.let { return it }
        }
        return Dns.SYSTEM.lookup(host)
    }

    private fun auto(host: String): List<InetAddress> {
        val sys = try { Dns.SYSTEM.lookup(host) } catch (e: UnknownHostException) { null }
        if (sys != null && usable(sys)) return sys
        // Bloqueado (127.0.0.1 / 0.0.0.0) o sin respuesta: se pregunta a un DNS seguro
        for (d in listOf(cloudflare, google)) {
            runCatching { d.lookup(host) }.getOrNull()?.takeIf { usable(it) }?.let { return it }
        }
        return sys ?: throw UnknownHostException(host)
    }

    private fun usable(list: List<InetAddress>): Boolean =
        list.isNotEmpty() && list.none { it.isLoopbackAddress || it.isAnyLocalAddress }
}
