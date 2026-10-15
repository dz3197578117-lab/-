package top.wkbin.zaomeng.app.shared

fun platformName(): String = "Android"

fun envVar(name: String): String? = System.getenv(name)
