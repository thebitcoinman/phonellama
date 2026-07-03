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

sealed class RouteResult {
  data class Local(
    val response: String,
    val tier: ComplexityTier,
    val modelName: String,
    val latencyMs: Long,
  ) : RouteResult()

  data class Remote(
    val response: String,
    val tier: ComplexityTier,
    val targetLabel: String,
    val latencyMs: Long,
  ) : RouteResult()

  data class Error(val message: String) : RouteResult()
}
