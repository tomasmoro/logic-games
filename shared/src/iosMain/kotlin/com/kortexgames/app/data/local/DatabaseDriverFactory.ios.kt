package com.kortexgames.app.data.local

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.data.local.db.LogicGamesDb

actual class DatabaseDriverFactory actual constructor(
    @Suppress("UNUSED_PARAMETER") context: PlatformContext,
) {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(LogicGamesDb.Schema, "logic_games.db")
}
