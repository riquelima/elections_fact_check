package com.factcheck.tv.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MiniMaxClient(private var apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val endpoint = "https://api.minimaxi.chat/v1/text/chatcompletion_v2"

    fun updateApiKey(newKey: String) {
        if (newKey.isNotBlank()) {
            this.apiKey = newKey.trim()
        }
    }

    /**
     * Envia o áudio transcrito da TV para verificar se há alegações factuais e checar a veracidade.
     */
    suspend fun checkTranscript(transcript: String): FactCheckResult? = withContext(Dispatchers.IO) {
        if (transcript.isBlank() || apiKey.isBlank()) {
            return@withContext null
        }

        val systemPrompt = """
            Você é um verificador de fatos em tempo real especializado em transmissões de TV aberta e jornalismo ao vivo no Brasil (GloboNews, TV Globo, CNN Brasil, BandNews).
            Sua missão é analisar o trecho ouvido da TV e:
            1. Determinar se há uma alegação factual verificável (dados econômicos, pesquisas, política, saúde, declarações oficiais). Ignore conversas informais ou saudações.
            2. Se houver alegação factual, classifique o veredito estritamente como:
               - "FALSO" (informação comprovadamente incorreta, inventada ou distorcida).
               - "ENGANOSO" (dado fora de contexto ou exagero desmentido).
               - "VERDADEIRO" (fato real condizente com as fontes oficiais).
            3. Resuma em 1 frase curta o que a TV disse ("claim_text") e em 1 frase clara qual é a realidade checada ("verified_fact").
            4. Indique a fonte oficial de autoridade ("source") (ex: "IBGE", "Banco Central", "TSE", "Ministério da Saúde", "Fato ou Fake").

            Responda EXCLUSIVAMENTE em formato JSON com o seguinte schema:
            {
              "has_claim": true,
              "verdict": "FALSO" | "ENGANOSO" | "VERDADEIRO",
              "claim_text": "...",
              "verified_fact": "...",
              "source": "...",
              "confidence": 0.95
            }
        """.trimIndent()

        val userPrompt = "Trecho ouvido da transmissão da TV:\n\"$transcript\""
        return@withContext executeChatQuery(systemPrompt, userPrompt)
    }

    /**
     * Radar de notícias ao vivo: gera ou checa declarações factuais recentes em debate nos jornais da TV (GloboNews/Canais).
     */
    suspend fun fetchLiveNewsClaim(): FactCheckResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null

        val systemPrompt = """
            Você é o motor de checagem em tempo real do TV Fact Checker para a televisão brasileira (GloboNews, CNN Brasil, TV Globo).
            Selecione uma alegação factual relevante sobre economia, dados oficiais, política ou eleições em debate no jornalismo recente.
            Pode ser classificada como "VERDADEIRO", "FALSO" ou "ENGANOSO".
            Responda EXCLUSIVAMENTE em formato JSON com o schema:
            {
              "has_claim": true,
              "verdict": "VERDADEIRO" | "FALSO" | "ENGANOSO",
              "claim_text": "...",
              "verified_fact": "...",
              "source": "...",
              "confidence": 0.96
            }
        """.trimIndent()

        val userPrompt = "Gere uma checagem factual ao vivo para quem está assistindo a cobertura jornalística na TV agora."
        return@withContext executeChatQuery(systemPrompt, userPrompt)
    }

    private fun executeChatQuery(systemPrompt: String, userPrompt: String): FactCheckResult? {
        val requestPayload = JsonObject().apply {
            addProperty("model", "MiniMax-Text-01")
            addProperty("temperature", 0.1)
            val messagesArray = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                })
            }
            add("messages", messagesArray)
        }

        val body = requestPayload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("MiniMaxClient", "Erro na API: ${response.code} - ${response.message}")
                    return null
                }

                val responseBody = response.body?.string() ?: return null
                val rootJson = gson.fromJson(responseBody, JsonObject::class.java)
                val choices = rootJson.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val message = choices[0].asJsonObject.getAsJsonObject("message")
                    val content = message?.get("content")?.asString ?: return null

                    val cleanJson = cleanMarkdownJson(content)
                    return gson.fromJson(cleanJson, FactCheckResult::class.java)
                }
            }
        } catch (e: Exception) {
            Log.e("MiniMaxClient", "Falha na requisição da IA: ${e.message}", e)
        }
        return null
    }

    private fun cleanMarkdownJson(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json")
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```")
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```")
        }
        return clean.trim()
    }
}
