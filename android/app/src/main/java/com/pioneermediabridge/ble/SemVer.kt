package com.pioneermediabridge.ble

/**
 * Semantic version with the subset of semver 2.0 the bridge needs.
 * Build metadata is parsed but ignored for ordering, as the specification requires.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        if (preRelease == null) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    companion object {
        val ZERO = SemVer(0, 0, 0)

        private val PATTERN = Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )

        fun parseOrNull(text: String?): SemVer? {
            val trimmed = text?.trim()?.removePrefix("v") ?: return null
            val match = PATTERN.matchEntire(trimmed) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            if (major > 255 || minor > 255 || patch > 255) return null
            return SemVer(major, minor, patch, match.groupValues[4].ifEmpty { null })
        }

        /** Unparsable or absent versions are treated as 0.0.0, per the BLE contract. */
        fun parseOrZero(text: String?): SemVer = parseOrNull(text) ?: ZERO

        private fun comparePreRelease(a: String?, b: String?): Int {
            if (a == null && b == null) return 0
            if (a == null) return 1
            if (b == null) return -1

            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrNull(i) ?: return -1
                val r = right.getOrNull(i) ?: return 1
                val ln = l.toIntOrNull()
                val rn = r.toIntOrNull()
                val result = when {
                    ln != null && rn != null -> ln.compareTo(rn)
                    ln != null -> -1
                    rn != null -> 1
                    else -> l.compareTo(r)
                }
                if (result != 0) return result
            }
            return 0
        }
    }
}
