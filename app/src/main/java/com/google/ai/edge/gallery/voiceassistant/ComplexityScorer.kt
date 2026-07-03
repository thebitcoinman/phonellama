/*
 * Copyright 2026 PhoneLlama
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.voiceassistant

enum class ComplexityTier {
  SIMPLE,
  MEDIUM,
  COMPLEX,
}

object ComplexityScorer {
  private val simpleKeywords =
    listOf("define", "convert", "calculate", "hello", "hi", "how much")
  private val complexKeywords =
    listOf(
      "write code",
      "implement",
      "refactor",
      "debug",
      "analyze",
      "compare in detail",
      "rest api",
      "full application",
      "step by step plan",
    )

  fun scoreComplexity(prompt: String): ComplexityTier {
    val tokens = prompt.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    val lower = prompt.lowercase()
    when {
      tokens > 150 || complexKeywords.any { it in lower } -> return ComplexityTier.COMPLEX
      tokens < 30 && simpleKeywords.any { it in lower } -> return ComplexityTier.SIMPLE
      tokens < 80 -> return ComplexityTier.MEDIUM
      else -> return ComplexityTier.COMPLEX
    }
  }
}
