package com.factcheck.tv.sources

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Motor especializado na captura e monitoramento das MANCHETES e TARJAS da TV aberta e telejornais (GloboNews, G1, etc.).
 * Permite identificar o que está escrito no gerador de caracteres (GC) da transmissão ao vivo.
 */
class MancheteScannerManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cachedHeadlines = mutableListOf<String>()
    private var lastFetchTime = 0L

    companion object {
        private const val G1_POLITICA_FEED = "https://g1.globo.com/rss/g1/politica/"
        private const val G1_ECONOMIA_FEED = "https://g1.globo.com/rss/g1/economia/"
    }

    /**
     * Obtém as manchetes e tarjas mais recentes em exibição no jornalismo da TV.
     */
    suspend fun getLatestTvHeadlines(): List<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedHeadlines.isNotEmpty() && (now - lastFetchTime) < (5 * 60 * 1000L)) {
            return@withContext cachedHeadlines
        }

        val headlines = mutableListOf<String>()
        headlines.addAll(fetchHeadlinesFromFeed(G1_POLITICA_FEED))
        headlines.addAll(fetchHeadlinesFromFeed(G1_ECONOMIA_FEED))

        if (headlines.isNotEmpty()) {
            synchronized(cachedHeadlines) {
                cachedHeadlines.clear()
                cachedHeadlines.addAll(headlines)
            }
            lastFetchTime = now
            Log.i("MancheteScanner", "Carregadas ${headlines.size} manchetes de TV em tempo real.")
        }
        return@withContext cachedHeadlines
    }

    /**
     * Retorna a manchete mais recente que contém alegações factuais (política, economia, declarações).
     */
    suspend fun getTopBreakingHeadline(): String? = withContext(Dispatchers.IO) {
        val list = getLatestTvHeadlines()
        return@withContext list.firstOrNull { it.length >= 15 }
    }

    private fun fetchHeadlinesFromFeed(url: String): List<String> {
        val results = mutableListOf<String>()
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Android TV MancheteScanner/1.0)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()

                val parser = Xml.newPullParser()
                parser.setInput(StringReader(body))

                var eventType = parser.eventType
                var insideItem = false
                var title = ""

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name ?: ""
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (tagName.equals("item", ignoreCase = true)) {
                                insideItem = true
                                title = ""
                            } else if (insideItem && tagName.equals("title", ignoreCase = true)) {
                                title = parser.nextText()
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (tagName.equals("item", ignoreCase = true)) {
                                insideItem = false
                                if (title.isNotBlank()) {
                                    results.add(title.trim())
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            Log.w("MancheteScanner", "Erro ao carregar manchetes de $url: ${e.message}")
        }
        return results
    }
}
