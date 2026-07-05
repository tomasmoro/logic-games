package com.example.kortexgames.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.kortexgames.core.audio.PlatformContext
import com.example.kortexgames.data.local.db.LogicGamesDb

actual class DatabaseDriverFactory actual constructor(
    @Suppress("UNUSED_PARAMETER") context: PlatformContext,
) {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(LogicGamesDb.Schema, "logic_games.db")
}
