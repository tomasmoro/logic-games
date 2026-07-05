package com.example.kortexgames

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform