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

import com.google.ai.edge.gallery.data.Model

object VoiceAssistantModels {
  const val E2B_PRIMARY = "Gemma-4-E2B-it"
  const val E4B_PRIMARY = "Gemma-4-E4B-it"

  /** Preference order — Gemma 4 first, then Gemma 3n fallbacks. */
  private val E2B_ALIASES =
    listOf(
      "Gemma-4-E2B-it",
      "Gemma-3n-E2B-it",
      "gemma-4-e2b-it",
      "gemma-3n-e2b-it",
    )
  private val E4B_ALIASES =
    listOf(
      "Gemma-4-E4B-it",
      "Gemma-3n-E4B-it",
      "gemma-4-e4b-it",
      "gemma-3n-e4b-it",
    )

  fun modelNameForTier(tier: ComplexityTier): String =
    when (tier) {
      ComplexityTier.SIMPLE -> E2B_PRIMARY
      ComplexityTier.MEDIUM, ComplexityTier.COMPLEX -> E4B_PRIMARY
    }

  /**
   * Pick the best on-device model for a tier:
   * 1. Already-loaded instance if it matches the tier
   * 2. Downloaded model in alias preference order (Gemma 4 before 3n)
   * 3. For simple tier, fall back to downloaded E4B if E2B is missing
   */
  fun findModel(
    allModels: List<Model>,
    tier: ComplexityTier,
    isDownloaded: (Model) -> Boolean,
    currentlyLoaded: Model? = null,
  ): Model? {
    val aliasOrder =
      when (tier) {
        ComplexityTier.SIMPLE -> E2B_ALIASES
        ComplexityTier.MEDIUM, ComplexityTier.COMPLEX -> E4B_ALIASES
      }

    currentlyLoaded?.takeIf { it.instance != null && matchesAnyAlias(it, aliasOrder) }?.let {
      return it
    }

    for (alias in aliasOrder) {
      allModels.firstOrNull { matches(it, alias) && isDownloaded(it) }?.let { return it }
    }

    // Cross-tier fallback: any downloaded Gemma beats an undownloaded ideal one.
    val fallbackAliases =
      when (tier) {
        ComplexityTier.SIMPLE -> E4B_ALIASES
        ComplexityTier.MEDIUM, ComplexityTier.COMPLEX -> E2B_ALIASES
      }
    for (alias in fallbackAliases) {
      allModels.firstOrNull { matches(it, alias) && isDownloaded(it) }?.let { return it }
    }

    // Catalog-only match (for error messages — not downloaded yet).
    for (alias in aliasOrder) {
      allModels.firstOrNull { matches(it, alias) }?.let { return it }
    }
    return null
  }

  private fun matches(model: Model, alias: String): Boolean =
    model.name.equals(alias, ignoreCase = true) ||
      model.displayName.equals(alias, ignoreCase = true)

  private fun matchesAnyAlias(model: Model, aliases: List<String>): Boolean =
    aliases.any { matches(model, it) }

  fun displayTierLabel(tier: ComplexityTier): String =
    when (tier) {
      ComplexityTier.SIMPLE -> "Simple"
      ComplexityTier.MEDIUM -> "Medium"
      ComplexityTier.COMPLEX -> "Complex"
    }
}
