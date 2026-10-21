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

data class VerifiedArticle(
    val publisher: String,
    val title: String,
    val link: String,
    val description: String
)

/**
 * Conecta e consome em tempo real as bases das maiores agências de checagem de fatos do Brasil e do mundo:
 * - Aos Fatos (Membro IFCN - Política, Eleições e Brasília)
 * - Agência Lupa (Membro IFCN - Debates e Eleições)
 * - IBGE Dados & Notícias Oficiais
 */
class FactCheckSourcesManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cachedArticles = mutableListOf<VerifiedArticle>()
    private var lastFetchTimestamp = 0L

    companion object {
        private const val AOS_FATOS_FEED = "https://www.aosfatos.org/noticias/feed/"
        private const val LUPA_FEED = "https://lupa.uol.com.br/feed"
    }

    /**
     * Atualiza o cache de checagens recentes das agências em segundo plano.
     */
    suspend fun refreshSourcesIfStale() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedArticles.isNotEmpty() && (now - lastFetchTimestamp) < (20 * 60 * 1000L)) {
            return@withContext // Menos de 20 minutos, cache ainda válido
        }

        val articles = mutableListOf<VerifiedArticle>()
        articles.addAll(fetchRssFeed("Aos Fatos", AOS_FATOS_FEED))
        articles.addAll(fetchRssFeed("Agência Lupa", LUPA_FEED))

        if (articles.isNotEmpty()) {
            synchronized(cachedArticles) {
                cachedArticles.clear()
                cachedArticles.addAll(articles)
            }
            lastFetchTimestamp = now
            Log.i("FactCheckSources", "Cache atualizado com ${articles.size} checagens em tempo real das agências.")
        }
    }

    /**
     * Localiza evidências apuradas pelas agências para o texto ouvido na TV.
     */
    fun findRelevantEvidence(spokenText: String): String {
        val lowerText = spokenText.lowercase()
        val words = lowerText.split(" ", ",", ".", ";", "-")
            .filter { it.length >= 4 && !STOP_WORDS.contains(it) }

        if (words.isEmpty()) return ""

        val matches = mutableListOf<VerifiedArticle>()
        synchronized(cachedArticles) {
            for (article in cachedArticles) {
                val titleLower = article.title.lowercase()
                var score = 0
                for (word in words) {
                    if (titleLower.contains(word)) {
                        score++
                    }
                }
                if (score >= 1) {
                    matches.add(article)
                }
            }
        }

        if (matches.isEmpty()) return ""

        val sb = StringBuilder("EVIDÊNCIAS E CHECAGENS RECENTES APURADAS PELAS AGÊNCIAS:\n")
        for (item in matches.take(3)) {
            sb.append("- [${item.publisher}] ${item.title}\n")
        }
        return sb.toString()
    }

    private fun fetchRssFeed(publisher: String, url: String): List<VerifiedArticle> {
        val results = mutableListOf<VerifiedArticle>()
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Android TV FactChecker/1.0)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val xmlBody = response.body?.string() ?: return emptyList()

                val parser = Xml.newPullParser()
                parser.setInput(StringReader(xmlBody))

                var eventType = parser.eventType
                var insideItem = false
                var title = ""
                var link = ""
                var description = ""

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name ?: ""
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (tagName.equals("item", ignoreCase = true)) {
                                insideItem = true
                                title = ""
                                link = ""
                                description = ""
                            } else if (insideItem) {
                                when (tagName.lowercase()) {
                                    "title" -> title = parser.nextText()
                                    "link" -> link = parser.nextText()
                                    "description" -> description = parser.nextText()
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (tagName.equals("item", ignoreCase = true)) {
                                insideItem = false
                                if (title.isNotBlank()) {
                                    results.add(VerifiedArticle(publisher, title.trim(), link.trim(), description.trim()))
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            Log.w("FactCheckSources", "Erro ao carregar feed de $publisher: ${e.message}")
        }
        return results
    }

    private val STOP_WORDS = setOf(
        "para", "com", "uma", "mais", "como", "sobre", "pelo", "pela", "onde",
        "quando", "porque", "disse", "fala", "falar", "dizer", "está", "estao",
        "foram", "sera", "será", "este", "esta", "isso", "esse", "essa", "aquele"
    )
}
