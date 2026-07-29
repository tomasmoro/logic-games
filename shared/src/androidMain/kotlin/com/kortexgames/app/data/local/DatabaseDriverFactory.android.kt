package com.kortexgames.app.data.local

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import com.kortexgames.app.core.audio.PlatformContext
import com.kortexgames.app.data.local.db.LogicGamesDb

actual class DatabaseDriverFactory actual constructor(
    private val context: PlatformContext,
) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(LogicGamesDb.Schema, context.context, "logic_games.db")
}
