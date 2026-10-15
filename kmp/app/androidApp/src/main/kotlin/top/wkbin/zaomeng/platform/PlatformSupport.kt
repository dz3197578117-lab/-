package top.wkbin.zaomeng.platform

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

@OptIn(ExperimentalTime::class)
fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun monotonicNanos(): Long = TimeSource.Monotonic.markNow().elapsedNow().inWholeNanoseconds

fun ByteArray.toHex(): String = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
