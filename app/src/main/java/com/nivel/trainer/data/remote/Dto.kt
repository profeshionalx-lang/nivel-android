package com.nivel.trainer.data.remote

import kotlinx.serialization.Serializable

/** DTO ответов API. Сериализация — kotlinx.serialization. Расширяется в A3–A5/B-задачах. */
@Serializable
data class HealthResponse(
    val status: String,
)
