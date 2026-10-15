package top.wkbin.zaomeng.platform

private val clientDiagnosticsLock = Any()

internal fun <T> withClientDiagnosticsLock(block: () -> T): T =
    synchronized(clientDiagnosticsLock) { block() }
