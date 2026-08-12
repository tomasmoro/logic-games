package com.kortexgames.app.core

import com.kortexgames.app.core.audio.PlatformContext

/**
 * ¿Estamos en una build de **depuración**?
 *
 * Sirve para dejar herramientas de diagnóstico dentro de la app sin que lleguen a
 * la tienda. Se resuelve preguntándole a la plataforma por la build en curso (y no
 * con una constante generada por Gradle) porque así no hay ningún interruptor que
 * alguien pueda dejarse encendido por error antes de publicar: en el AAB/IPA de
 * release la respuesta es siempre false, sin depender de la configuración.
 *
 * @param context contexto de plataforma (Android lo necesita; iOS lo ignora).
 */
expect fun isDebugBuild(context: PlatformContext): Boolean
