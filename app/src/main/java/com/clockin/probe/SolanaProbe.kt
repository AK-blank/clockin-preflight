package com.clockin.probe

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction

/**
 * Touches the Solana Kotlin SDK from production code so the compiler and linker resolve it.
 * Each call is guarded so a beta-SDK signature change degrades to a diagnostic string
 * instead of breaking the APK build.
 */
object SolanaProbe {

    fun sdkMarker(): String {
        val parts = mutableListOf<String>()

        parts += runCatching {
            val key = SolanaPublicKey(ByteArray(32))
            "publicKey=ok(${key.hashCode() != 0})"
        }.getOrElse { "publicKey:${it::class.simpleName}" }

        parts += runCatching {
            val tx = Transaction.from(byteArrayOf())
            "tx=ok(${tx.signatures.size})"
        }.getOrElse { "tx:${it::class.simpleName}" }

        return parts.joinToString(",")
    }
}
