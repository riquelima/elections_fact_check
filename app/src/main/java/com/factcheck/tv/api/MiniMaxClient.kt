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
    private val primaryEndpoint = "https://api.minimax.io/v1/chat/completions"
    private val fallbackEndpoint = "https://api.minimaxi.chat/v1/text/chatcompletion_v2"
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
     * Envia o áudio transcrito da TV e print da tela para verificar contra agências de fact-checking e bases oficiais
     * utilizando o modelo multimodal MiniMax-M3.1-Flash com 1 milhão de tokens de contexto.
     */
    suspend fun checkTranscript(
        transcript: String,
        evidenceContext: String = "",
        screenshotBase64: String? = null
    ): FactCheckResult? = withContext(Dispatchers.IO) {
        if (transcript.isBlank() || apiKey.isBlank()) {
            return@withContext null
        }

        val systemPrompt = """
            Você é o motor de checagem em tempo real especializado em telejornalismo ao vivo (GloboNews, TV aberta, CNN Brasil) e debates políticos no Brasil.
            Suas fontes de referência mandatárias são as agências de checagem brasileiras certificadas (Agência Lupa, Aos Fatos, Projeto Comprova, G1 Fato ou Fake) e órgãos oficiais de Estado (TSE, STF, IBGE, Banco Central, Ministérios).

            DIRETRIZ DE VERIFICAÇÃO MANDATÁRIA:
            1. O telespectador está assistindo à TV ao vivo. Sempre que houver uma notícia, debate, manchete na tela ou declaração de interesse público (política, economia, índices, declarações de Lula, Bolsonaro, ministros, etc.), defina SEMPRE "has_claim": true!
            2. O telespectador quer ver o card na tela tanto para notícias VERDADEIRAS quanto FALSAS ou ENGANOSAS:
               - "VERDADEIRO": a informação dita ou mostrada condiz com a realidade, dados oficiais ou fatos apurados.
               - "FALSO": a informação é incorreta, boato ou comprovadamente desmentida.
               - "ENGANOSO": dado fora de contexto, exagero tendencioso ou distorção.
            3. Tanto para "VERDADEIRO", "FALSO" ou "ENGANOSO", mantenha SEMPRE "has_claim": true para que o card correspondente (verde, vermelho ou amarelo) seja projetado na tela da TV!
            4. Se uma imagem da tela da TV (screenshot) for fornecida, leia prioritariamente as tarjas, o gerador de caracteres (GC), números e gráficos exibidos no televisor para confirmar o contexto da notícia.
            5. "claim_text": o que foi dito ou exibido na TV (frase curta e clara).
            6. "verified_fact": a explicação factual em 1 ou 2 frases com os dados reais confirmados ou corrigidos.
            7. Apenas retorne "has_claim": false se o áudio for exclusivamente silêncio, música de fundo, ou saudações curtas ("boa noite", "olá") sem nenhuma notícia ou fato associado.

            Responda EXCLUSIVAMENTE em formato JSON com o seguinte schema:
            {
              "has_claim": true,
              "verdict": "VERDADEIRO" | "FALSO" | "ENGANOSO",
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

        return@withContext executeChatQuery(systemPrompt, promptBuilder.toString(), screenshotBase64)
    }

    private fun executeChatQuery(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String? = null
    ): FactCheckResult? {
        val requestPayload = JsonObject().apply {
            addProperty("model", "MiniMax-M3.1-Flash")
            addProperty("reasoning_effort", "low") // Otimiza a profundidade de pensamento para resposta ultrarrápida na TV
            addProperty("max_completion_tokens", 800)
            val messagesArray = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })

                val userMessage = JsonObject().apply {
                    addProperty("role", "user")
                    if (screenshotBase64.isNullOrBlank()) {
                        addProperty("content", userPrompt)
                    } else {
                        val contentArray = com.google.gson.JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", userPrompt)
                            })
                            add(JsonObject().apply {
                                addProperty("type", "image_url")
                                val imgObj = JsonObject().apply {
                                    addProperty("url", screenshotBase64)
                                    addProperty("detail", "low")
                                }
                                add("image_url", imgObj)
                            })
                        }
                        add("content", contentArray)
                    }
                }
                add(userMessage)
            }
            add("messages", messagesArray)
        }

        val jsonString = requestPayload.toString()

        // 1. Tenta o endpoint global primário da MiniMax (OpenAI-compatible)
        val primaryResult = sendPostRequest(primaryEndpoint, jsonString)
        if (primaryResult != null) return primaryResult

        // 2. Se falhar, executa failover automático para o endpoint secundário
        Log.w("MiniMaxClient", "Executando failover para endpoint secundário...")
        return sendPostRequest(fallbackEndpoint, jsonString)
    }

    private fun sendPostRequest(targetUrl: String, jsonPayload: String): FactCheckResult? {
        val body = jsonPayload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(targetUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("MiniMaxClient", "Erro na API ($targetUrl): ${response.code} - ${response.message}")
                    return null
                }

                val responseBody = response.body?.string() ?: return null
                val rootJson = gson.fromJson(responseBody, JsonObject::class.java)
                val choices = rootJson.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val message = choices[0].asJsonObject.getAsJsonObject("message")
                    var content = message?.get("content")?.asString ?: ""

                    // Se content estiver vazio mas reasoning_content existir, aproveita o texto
                    if (content.isBlank() && message?.has("reasoning_content") == true) {
                        content = message.get("reasoning_content")?.asString ?: ""
                    }

                    val cleanJson = cleanMarkdownJson(content)
                    return gson.fromJson(cleanJson, FactCheckResult::class.java)
                }
            }
        } catch (e: Exception) {
            Log.e("MiniMaxClient", "Falha na requisição para $targetUrl: ${e.message}")
        }
        return null
    }

    private fun cleanMarkdownJson(raw: String): String {
        var clean = raw.trim()
        // Remove tags <think>...</think> se existirem
        if (clean.contains("</think>")) {
            clean = clean.substringAfter("</think>").trim()
        }
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json")
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```")
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```")
        }

        // Garante isolamento estrito do primeiro '{' até o último '}'
        val firstBrace = clean.indexOf('{')
        val lastBrace = clean.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            clean = clean.substring(firstBrace, lastBrace + 1)
        }
        return clean.trim()
    }
}
