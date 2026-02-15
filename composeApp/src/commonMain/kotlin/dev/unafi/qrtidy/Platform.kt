package dev.unafi.qrtidy

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform