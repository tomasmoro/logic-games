package com.example.kortexgames.data.local

import app.cash.sqldelight.db.SqlDriver
import com.example.kortexgames.core.audio.PlatformContext
import com.example.kortexgames.data.local.db.LogicGamesDb

/** Crea el SqlDriver nativo (AndroidSqliteDriver / NativeSqliteDriver). */
expect class DatabaseDriverFactory(context: PlatformContext) {
    fun create(): SqlDriver
}

/** Punto único de construcción de la base de datos local. */
fun createDatabase(factory: DatabaseDriverFactory): LogicGamesDb =
    LogicGamesDb(factory.create())
