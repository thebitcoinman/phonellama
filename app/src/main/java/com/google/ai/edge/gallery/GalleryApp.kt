/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.ai.edge.gallery.ui.common.DeviceStatsBar
import com.google.ai.edge.gallery.ui.common.ModelLoadProgressBar
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.navigation.GalleryNavHost

/** Top level composable representing the main screen of the application. */
@Composable
fun GalleryApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  // Stats bar + progress are placed ABOVE the nav host in a Column so they never
  // overlap content — the nav host fills the remaining vertical space.
  Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
    DeviceStatsBar(modelManagerViewModel = modelManagerViewModel)
    ModelLoadProgressBar(modelManagerViewModel = modelManagerViewModel)
    GalleryNavHost(
      navController = navController,
      modelManagerViewModel = modelManagerViewModel,
      modifier = Modifier.weight(1f),
    )
  }
}
