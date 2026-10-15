package top.wkbin.zaomeng.client.platform

import io.ktor.client.engine.HttpClientEngine
import okio.ByteString.Companion.toByteString
import kotlin.time.TimeSource

/** Platform services used by the client layer independently of the embedded backend. */
fun clientBase64Encode(bytes: ByteArray): String = bytes.toByteString().base64()

fun clientMonotonicNanos(): Long = TimeSource.Monotonic.markNow().elapsedNow().inWholeNanoseconds
