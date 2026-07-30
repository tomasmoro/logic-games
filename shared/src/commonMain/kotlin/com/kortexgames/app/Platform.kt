package com.kortexgames.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform