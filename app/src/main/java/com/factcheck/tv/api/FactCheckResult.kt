package com.factcheck.tv.api

import com.google.gson.annotations.SerializedName

/**
 * Result model returned by the MiniMax Fact-Checking Engine.
 */
data class FactCheckResult(
    @SerializedName("has_claim")
    val hasClaim: Boolean = false,

    @SerializedName("verdict")
    val verdict: String = "INDEFINIDO", // FALSO, ENGANOSO, VERDADEIRO, DESCONHECIDO

    @SerializedName("claim_text")
    val claimText: String = "",

    @SerializedName("verified_fact")
    val verifiedFact: String = "",

    @SerializedName("source")
    val source: String = "Fontes Públicas e Agências de Checagem",

    @SerializedName("confidence")
    val confidence: Float = 0.0f
)
