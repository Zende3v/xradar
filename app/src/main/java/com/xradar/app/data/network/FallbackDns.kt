package com.xradar.app.data.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The phone's DNS first. When it cannot find a host — some home boxes wrongly answer that
 * the backend's name does not exist — the address is asked again over DNS-over-HTTPS
 * (Cloudflare, then Google), reached by IP address so no DNS is needed to get there.
 * TLS still checks the real host's certificate, so a wrong address could never be used.
 * Addresses found that way are reused for their TTL. Set on every OkHttp client of the app.
 */
object FallbackDns : Dns {

    private class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        // Once the phone's DNS failed for a host, skip it until the rescued answer expires.
        cache[hostname]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.addresses }
        val systemFailure = try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            e
        }
        val rescued = resolveOverHttps(hostname) ?: throw systemFailure
        cache[hostname] = rescued
        return rescued.addresses
    }

    /** A records first, then AAAA, from each provider in turn; null when none answers. */
    private fun resolveOverHttps(hostname: String): Entry? {
        for (endpoint in ENDPOINTS) {
            for (type in RECORD_TYPES) {
                val records = query(endpoint, hostname, type) ?: break
                if (records.isEmpty()) continue
                val ttl = records.minOf { it.second }.coerceIn(MIN_TTL_S, MAX_TTL_S)
                val addresses = records.mapNotNull { (ip, _) ->
                    try {
                        InetAddress.getByName(ip)
                    } catch (e: UnknownHostException) {
                        null
                    }
                }
                if (addresses.isNotEmpty()) {
                    return Entry(addresses, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ttl))
                }
            }
        }
        return null
    }

    /** The records of [type] for [hostname] (empty if none); null when the provider fails. */
    private fun query(endpoint: String, hostname: String, type: Int): List<Pair<String, Long>>? {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("name", hostname)
            .addQueryParameter("type", type.toString())
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        return try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) null else parseAnswers(r.body?.string().orEmpty(), type)
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * The (address, TTL) pairs of [type] in a DNS JSON answer. The answer is a flat list of
     * records, so a few patterns read it; only literal IP addresses are kept, which means
     * turning them into [InetAddress] never triggers another DNS lookup.
     */
    internal fun parseAnswers(json: String, type: Int): List<Pair<String, Long>> {
        val section = ANSWER_SECTION.find(json)?.groupValues?.get(1) ?: return emptyList()
        return RECORD.findAll(section).mapNotNull { match ->
            val record = match.value
            val recordType = TYPE.find(record)?.groupValues?.get(1)?.toIntOrNull()
            val data = DATA.find(record)?.groupValues?.get(1)
            val ttl = TTL.find(record)?.groupValues?.get(1)?.toLongOrNull() ?: MIN_TTL_S
            val literal = when (type) {
                TYPE_A -> data?.takeIf { IPV4.matches(it) }
                TYPE_AAAA -> data?.takeIf { ':' in it && IPV6_CHARS.matches(it) }
                else -> null
            }
            if (recordType == type && literal != null) literal to ttl else null
        }.toList()
    }

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private val RECORD_TYPES = intArrayOf(TYPE_A, TYPE_AAAA)

    /** JSON DNS endpoints, by IP address: reaching them needs no DNS at all. */
    private val ENDPOINTS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")

    private const val MIN_TTL_S = 60L
    private const val MAX_TTL_S = 3_600L

    private val ANSWER_SECTION = Regex(""""Answer"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
    private val RECORD = Regex("""\{[^{}]*}""")
    private val TYPE = Regex(""""type"\s*:\s*(\d+)""")
    private val TTL = Regex(""""TTL"\s*:\s*(\d+)""")
    private val DATA = Regex(""""data"\s*:\s*"([^"]+)""")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    private val IPV6_CHARS = Regex("""[0-9a-fA-F:.]+""")
}
