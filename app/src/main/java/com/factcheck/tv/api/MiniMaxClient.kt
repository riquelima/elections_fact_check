package com.factcheck.tv.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MiniMaxClient(private var apiKey: String = DEFAULT_KEY) {

    companion object {
        const val DEFAULT_KEY = "sk-cp-meaN0PHZdGi3-5gZffia9b6PyDIh27vyk54LwG6gw965dFLWoIHowFo19rTqoHdbxhaQezJlMMBgTEYhNni51sJnMWCcPHIKtCg4GRY-pGMmrXarNIxxGQA"
    }

    init {
        if (apiKey.isBlank()) {
            apiKey = DEFAULT_KEY
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val endpoint = "https://api.minimaxi.chat/v1/text/chatcompletion_v2"
    private val asrEndpoint = "https://api.minimaxi.chat/v1/speech_to_text"

    fun updateApiKey(newKey: String) {
        if (newKey.isNotBlank()) {
            this.apiKey = newKey.trim()
        }
    }

    /**
     * Transcreve um trecho de áudio WAV via motor neural ASR da IA em tempo real.
     */
    suspend fun transcribeAudioChunk(wavBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (wavBytes.isEmpty() || apiKey.isBlank()) return@withContext null

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "asr-1.0")
            .addFormDataPart(
                "file", "stream.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(asrEndpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("MiniMaxClient", "Erro no ASR da IA: ${response.code} - ${response.message}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                val text = json.get("text")?.asString?.trim() ?: ""
                return@withContext text
            }
        } catch (e: Exception) {
            Log.e("MiniMaxClient", "Falha na transcrição neural: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Envia o áudio transcrito da TV para verificar contra agências de fact-checking e bases oficiais.
     */
    suspend fun checkTranscript(transcript: String, evidenceContext: String = ""): FactCheckResult? = withContext(Dispatchers.IO) {
        if (transcript.isBlank() || apiKey.isBlank()) {
            return@withContext null
        }

        val systemPrompt = """
            Você é o motor de checagem em tempo real especializado em debates políticos, eleições e telejornalismo ao vivo no Brasil (GloboNews, TV Globo, CNN Brasil, BandNews).
            Suas fontes de referência mandatárias são as agências certificadas pela IFCN (Agência Lupa, Aos Fatos, Projeto Comprova, G1 Fato ou Fake, UOL Confere, Estadão Verifica, Reuters Fact Check) e órgãos oficiais de Estado (TSE, STF, IBGE, Banco Central do Brasil, CGU, Ministério da Fazenda, Congresso Nacional).

            Sua missão é analisar o trecho exato ouvido da transmissão da TV:
            1. Se houver alegação factual, dados econômicos, citações sobre adversários políticos, declarações de campanha ou medidas de governo, classifique o veredito estritamente como:
               - "FALSO" (informação comprovadamente incorreta, inventada ou distorcida).
               - "ENGANOSO" (dado fora de contexto, exagero desmentido ou informação incompleta).
               - "VERDADEIRO" (fato real condizente com as apurações e dados oficiais).
            2. Resuma em 1 frase curta o que foi dito na TV ("claim_text") e em 1 frase clara qual é a realidade checada ("verified_fact").
            3. Indique a fonte de autoridade ("source") (ex: "Aos Fatos", "Agência Lupa", "TSE", "G1 Fato ou Fake", "IBGE", "Projeto Comprova").
            4. Se o trecho não contiver nenhuma alegação factual verificável (apenas comentários informais, saudações, piadas), retorne "has_claim": false.

            Responda EXCLUSIVAMENTE em formato JSON com o seguinte schema:
            {
              "has_claim": true,
              "verdict": "FALSO" | "ENGANOSO" | "VERDADEIRO",
              "claim_text": "...",
              "verified_fact": "...",
              "source": "...",
              "confidence": 0.98
            }
        """.trimIndent()

        val promptBuilder = StringBuilder()
        promptBuilder.append("Trecho ouvido da transmissão da TV:\n\"$transcript\"")
        if (evidenceContext.isNotBlank()) {
            promptBuilder.append("\n\n").append(evidenceContext)
        }

        return@withContext executeChatQuery(systemPrompt, promptBuilder.toString())
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
