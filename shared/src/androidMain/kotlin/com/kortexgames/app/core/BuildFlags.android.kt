package com.kortexgames.app.core

import android.content.pm.ApplicationInfo
import com.kortexgames.app.core.audio.PlatformContext

/**
 * En Android se lee el flag `debuggable` del propio APK instalado.
 *
 * No se usa `BuildConfig.DEBUG` porque el `BuildConfig` que importa es el de
 * `androidApp` y este módulo (`shared`) no lo ve; además, el flag del
 * `ApplicationInfo` describe la app **realmente instalada**, que es justo lo que
 * queremos comprobar.
 */
actual fun isDebugBuild(context: PlatformContext): Boolean =
    context.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
