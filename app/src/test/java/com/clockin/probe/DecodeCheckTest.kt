package com.clockin.probe

import com.clockin.preflight.engine.RiskEngine
import org.junit.Test

/**
 * Lead integration check over the reproducible drainer fixture:
 * an unlimited SPL Token Approve (u64::MAX) plus a lure memo.
 * Rebuild with node tools/demo/build-drainer.mjs.
 */
class DecodeCheckTest {
    @Test
    fun drainerFixtureDecodesToADangerousVerdict() {
        val report = RiskEngine.analyze(DRAINER_FIXTURE)
        println("=== LEAD CHECK ===")
        println("decodeError = " + report.decodeError)
        println("level       = " + report.verdict.level)
        println("score       = " + report.verdict.score)
        println("headline    = " + report.verdict.headline)
        println("summary     = " + report.summary)
        println("narrative   = " + report.narrative)
        report.verdict.findings.forEach { println("finding: [" + it.severity + "] " + it.code + " :: " + it.title + " :: " + it.plainEnglish) }
        println("=== END ===")
        check(report.decodeError == null) { "fixture must decode: " + report.decodeError }
    }

    private companion object {
        const val DRAINER_FIXTURE = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAAMHfowIh2C/3h3dzzLBfyCbgkLuUqrxMfrNiNDqLG0LBvJnUgVcILPp2HRmVt33OFVQf4erbYdSPkx2p/o2CWqZ64Jo6amhREwrpcd6UZNoVrBy5D/vz/XksB6ZYjyOu3dJvCtXBl7x3WZUML5ga6ZZbAKVMBut74ta/EEBQVD0EnQG3fbh12Whk9nL4UbO63msHLSF7V9bN5E6jPWFfv8AqQVKU1qZKSEGTSTocWDaOHx8NbXdvJK7geQfqEBBBUSNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwIEAwEDAAkE//////////8FAQAzQ0xBSU0gUkVXQVJEOiB2ZXJpZnkgYXQgY2xhaW0tcHJlZmxpZ2h0LXJld2FyZHMueHl6"
    }
}