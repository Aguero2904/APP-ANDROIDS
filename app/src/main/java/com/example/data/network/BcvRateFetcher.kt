package com.example.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit

object BcvRateFetcher {
    private const val TAG = "BcvRateFetcher"
    
    // Create a trust-all OkHttpClient to bypass Venezuelan SSL root issues
    private val client: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL").apply {
                init(null, trustAllCerts, SecureRandom())
            }
            
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followSslRedirects(true)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing permissive OkHttpClient: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followSslRedirects(true)
                .build()
        }
    }

    /**
     * Attempts to fetch the exchange rate from BCV.
     * 1. First tries scraping the official BCV website (using SSL-bypass client).
     * 2. If it fails, calls the AlCambio GraphQL API.
     * 3. If that fails, falls back to the public API mirror.
     */
    suspend fun fetchLatestRate(): Double? {
        return withContext(Dispatchers.IO) {
            // 1. Try scraping from BCV Page
            try {
                val rate = scrapeBcvWebsite()
                if (rate != null && rate > 0) {
                    Log.d(TAG, "Successfully scraped BCV rate from official website: $rate")
                    return@withContext rate
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed scraping official BCV website: ${e.message}. Trying AlCambio...")
            }

            // 2. Try AlCambio GraphQL API
            try {
                val rate = fetchFromAlCambioApi()
                if (rate != null && rate > 0) {
                    Log.d(TAG, "Successfully fetched BCV rate from AlCambio GraphQL API: $rate")
                    return@withContext rate
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed fetching from AlCambio API: ${e.message}. Trying Backup API...")
            }

            // 3. Fallback to ve.dolarapi.com
            try {
                val rate = fetchFromDolarApi()
                if (rate != null && rate > 0) {
                    Log.d(TAG, "Successfully fetched BCV rate from backup API: $rate")
                    return@withContext rate
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch BCV rate from fallback API: ${e.message}")
            }

            return@withContext null
        }
    }

    private fun scrapeBcvWebsite(): Double? {
        val request = Request.Builder()
            .url("https://www.bcv.org.ve/")
            // It is VERY important to add a User-Agent, because BCV blocks standard Java/OkHttp agents
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null

            // Look for id="dolar" and capture the first decimal after it
            // The HTML usually contains <div id="dolar"> ... <strong> 36,1234 </strong>
            val regex = """id="dolar"[\s\S]*?<strong>\s*([0-9.,]+)\s*</strong>""".toRegex()
            var match = regex.find(html)
            
            if (match == null) {
                // Try a wider search: just the first decimal after id="dolar"
                val regexFallback = """id="dolar"[\s\S]*?([0-9]+,[0-9]+)""".toRegex()
                match = regexFallback.find(html)
            }

            match?.let {
                val valStr = it.groupValues[1].trim()
                // Convert e.g. "36,16310000" or "36.163,10" to standard double "36.1631"
                // In Venezuela, thousand separators are dots and decimals are commas, but sometimes there's no thousands.
                val cleanStr = if (valStr.count { c -> c == '.' } > 0 && valStr.count { c -> c == ',' } > 0) {
                    valStr.replace(".", "").replace(",", ".")
                } else {
                    valStr.replace(",", ".")
                }
                return cleanStr.toDoubleOrNull()
            }
        }
        return null
    }

    private fun fetchFromAlCambioApi(): Double? {
        val graphqlPayload = """
            {"query":"query getCountryConversions(\u0024countryCode: String!) { getCountryConversions(payload: { countryCode: \u0024countryCode }) { conversionRates { official baseValue type rateCurrency { code } } } }","variables":{"countryCode":"VE"}}
        """.trimIndent()

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = graphqlPayload.toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://api.alcambio.app/graphql")
            .post(requestBody)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val jsonStr = response.body?.string() ?: return null
            val root = JSONObject(jsonStr)
            val data = root.optJSONObject("data") ?: return null
            val getCountryConversions = data.optJSONObject("getCountryConversions") ?: return null
            val conversionRates = getCountryConversions.optJSONArray("conversionRates") ?: return null

            var backupRate: Double? = null
            for (i in 0 until conversionRates.length()) {
                val entry = conversionRates.optJSONObject(i) ?: continue
                val official = entry.optBoolean("official", false)
                val baseValue = entry.optDouble("baseValue", 0.0)
                val type = entry.optString("type", "")
                val rateCurrency = entry.optJSONObject("rateCurrency") ?: continue
                val code = rateCurrency.optString("code", "")

                if (code == "USD") {
                    // Secondary represents the official BCV rate
                    if (official && type == "SECONDARY" && baseValue > 0) {
                        return baseValue / 10.0
                    }
                    if (official && type == "OTHER" && baseValue > 0) {
                        backupRate = baseValue / 10.0
                    }
                }
            }
            return backupRate
        }
    }

    private fun fetchFromDolarApi(): Double? {
        val request = Request.Builder()
            .url("https://ve.dolarapi.com/v1/dolares/bcv")
            .addHeader("User-Agent", "CobranzaWifiApp/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val jsonStr = response.body?.string() ?: return null
            val json = JSONObject(jsonStr)
            
            // ve.dolarapi.com contains "promedio", "compra", "venta"
            if (json.has("promedio")) {
                return json.getDouble("promedio")
            } else if (json.has("compra")) {
                return json.getDouble("compra")
            }
        }
        return null
    }
}
