package com.catalystradar.application.clustering

import kotlin.math.sqrt

/**
 * Cosine similarity in [-1, 1]. Zero vectors score 0 instead of NaN so
 * degenerate embeddings fail closed (no match) rather than erroring.
 */
fun cosineSimilarity(a: List<Float>, b: List<Float>): Double {
    require(a.size == b.size) { "vectors must share dimensionality" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denominator = sqrt(normA) * sqrt(normB)
    if (denominator == 0.0) return 0.0
    return (dot / denominator).coerceIn(-1.0, 1.0)
}
