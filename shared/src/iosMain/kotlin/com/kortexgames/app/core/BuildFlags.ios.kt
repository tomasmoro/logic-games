package com.kortexgames.app.core

import com.kortexgames.app.core.audio.PlatformContext
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * En iOS lo responde el propio runtime de Kotlin/Native: `isDebugBinary` es true en
 * el framework compilado en modo debug y false en el de release, que es exactamente
 * la distinción que se busca (no depende de Xcode ni de flags del proyecto iOS).
 */
@OptIn(ExperimentalNativeApi::class)
actual fun isDebugBuild(context: PlatformContext): Boolean = Platform.isDebugBinary
