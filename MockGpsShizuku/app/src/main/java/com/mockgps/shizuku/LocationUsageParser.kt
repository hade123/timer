package com.mockgps.shizuku

import android.os.Build

/**
 * Reads the output of `dumpsys location` and lists, per location provider
 * (gps / network / fused / passive ...), which installed apps are using it
 * right now and which used it since the phone started.
 *
 * The dumpsys format differs between Android versions, so parsing is loose:
 * it tracks section headers by indentation and picks out any token that is
 * the name of an installed package.
 */
object LocationUsageParser {

    private val pkgRe = Regex("""(?<![\w.])([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)""")
    private val providerHdr = Regex("""^\s*([a-z_]+) provider\b[^:]*:\s*$""")
    private val historySub = Regex("""^([a-z_]+):\s*$""")

    data class Result(
        val active: Map<String, Set<String>>,
        val history: Map<String, Set<String>>,
    )

    fun parse(raw: String, installed: Set<String>, self: String): Result {
        val active = linkedMapOf<String, MutableSet<String>>()
        val history = linkedMapOf<String, MutableSet<String>>()
        var mode = 0            // 0 = none, 1 = "<x> provider:" section, 2 = history section
        var modeIndent = -1
        var provider: String? = null
        var histProvIndent = -1

        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            val t = line.trim()

            if (mode != 0 && indent <= modeIndent) {
                mode = 0; provider = null
            }
            if (t.startsWith("Historical Aggregate", ignoreCase = true)) {
                mode = 2; modeIndent = indent; provider = null; histProvIndent = -1
                continue
            }
            if (mode != 2) {
                val ph = providerHdr.find(line)
                if (ph != null) {
                    mode = 1; modeIndent = indent; provider = ph.groupValues[1]
                    continue
                }
            }
            if (mode == 2) {
                val sub = historySub.find(t)
                if (sub != null && (histProvIndent == -1 || indent <= histProvIndent)) {
                    provider = sub.groupValues[1]; histProvIndent = indent
                    continue
                }
            }
            val p = provider ?: continue
            val target = when (mode) {
                1 -> active
                2 -> history
                else -> continue
            }
            for (m in pkgRe.findAll(line)) {
                val pkg = m.groupValues[1]
                if (pkg != self && pkg in installed) {
                    target.getOrPut(p) { linkedSetOf() }.add(pkg)
                }
            }
        }
        return Result(active, history)
    }

    fun buildReport(r: Result): String {
        val sb = StringBuilder()
        sb.append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

        sb.append("== Using location NOW ==\n")
        appendSection(sb, r.active)
        sb.append("\n== Used location since the phone started ==\n")
        appendSection(sb, r.history)

        val gms = "com.google.android.gms"
        val gmsUses = (r.active.entries + r.history.entries)
            .filter { gms in it.value }.map { it.key }.distinct()

        sb.append("\n== How to read this ==\n")
        sb.append("• gps → the app reads GPS directly. The fake location should work.\n")
        sb.append("• fused → Android's combined location (GPS + network). Should work.\n")
        sb.append("• network → location from Wi-Fi and cell towers. Can reveal your real AREA.\n")
        sb.append("• Your app NOT in the list → it asks Google Play services ($gms) instead. ")
        sb.append("Google can work out your area from nearby Wi-Fi and cell towers by itself.\n")
        if (gmsUses.isNotEmpty()) {
            sb.append("  Google Play services here uses: ${gmsUses.joinToString()}\n")
        }
        sb.append("• The app shows the fake spot but refuses it or shows an error → it detects mock location.\n")
        sb.append("\nTip: open the app you want to test first, then come back and tap this button.\n")
        return sb.toString()
    }

    private fun appendSection(sb: StringBuilder, m: Map<String, Set<String>>) {
        if (m.isEmpty()) {
            sb.append("(nothing found)\n")
            return
        }
        for ((prov, pkgs) in m) {
            sb.append("[$prov]\n")
            for (p in pkgs) sb.append("   $p\n")
        }
    }
}
