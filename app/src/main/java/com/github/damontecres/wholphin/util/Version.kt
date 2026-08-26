package com.github.damontecres.wholphin.util

import kotlinx.serialization.Serializable

/**
 * Represents a version in the format of `<major>.<minor>.<patch>-<numCommits>-g<gitSha>` as output by `git describe`
 */
@Serializable
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val numCommits: Int? = null,
    val hash: String? = null,
) {
    /**
     * Is this version at least the given version
     */
    fun isAtLeast(version: Version): Boolean {
        if (this.major > version.major) {
            return true
        } else if (this.major == version.major) {
            if (this.minor > version.minor) {
                return true
            } else if (this.minor == version.minor) {
                if (this.patch > version.patch) {
                    return true
                } else if (this.patch == version.patch) {
                    if (this.compareNumCommits(version) >= 0) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Is this greater than the given version (and not equal to!)
     */
    fun isGreaterThan(version: Version): Boolean {
        if (this.major > version.major) {
            return true
        } else if (this.major == version.major) {
            if (this.minor > version.minor) {
                return true
            } else if (this.minor == version.minor) {
                if (this.patch > version.patch) {
                    return true
                } else if (this.patch == version.patch) {
                    if (this.compareNumCommits(version) > 0) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Is this less than the given version (and not equal to!)
     */
    fun isLessThan(version: Version): Boolean = this != version && isEqualOrBefore(version)

    /**
     * Is this equal to or before the specified version
     */
    fun isEqualOrBefore(version: Version): Boolean = !isGreaterThan(version)

    private fun compareNumCommits(version: Version): Int = (this.numCommits ?: 0) - (version.numCommits ?: 0)

    override fun toString(): String =
        if (numCommits != null && numCommits > 0 && hash != null) {
            "v$major.$minor.$patch-$numCommits-g$hash"
        } else {
            "v$major.$minor.$patch"
        }

    companion object {
        private val VERSION_REGEX = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)(-(\\d+)-g([a-zA-Z0-9]+))?")

        /**
         * Parse a version string throwing if it is invalid
         */
        fun fromString(version: String): Version {
            val v = tryFromString(version)
            if (v == null) {
                throw IllegalArgumentException(version)
            } else {
                return v
            }
        }

        /**
         * Parse a version string, tolerating trailing junk after a valid version.
         *
         * [tryFromString] uses `matchEntire`, so a versionName carrying more than one
         * `-<n>-g<sha>` suffix fails outright. That happens when a release is tagged with
         * the OUTPUT of `git describe` rather than a plain `vX.Y.Z`: the next describe
         * appends a second suffix, producing e.g. `1.0.6-23-ge2420b3e-0-ge2420b3e`.
         *
         * A malformed version must never be fatal - [fromString] throwing on it took the
         * whole Settings page down, because the update check reads the installed version
         * first. This falls back to the leading valid portion so callers get a usable
         * version instead of an exception.
         */
        fun fromStringLenient(version: String?): Version? {
            if (version == null) {
                return null
            }
            return tryFromString(version) ?: VERSION_REGEX.find(version)?.let { m ->
                Version(
                    m.groups[1]!!.value.toInt(),
                    m.groups[2]!!.value.toInt(),
                    m.groups[3]!!.value.toInt(),
                    m.groups[5]?.value?.toInt(),
                    m.groups[6]?.value,
                )
            }
        }

        /**
         * Attempt to parse a version string or else return null
         */
        fun tryFromString(version: String?): Version? {
            if (version == null) {
                return null
            }
            val m = VERSION_REGEX.matchEntire(version)
            return if (m == null) {
                null
            } else {
                val major = m.groups[1]!!.value.toInt()
                val minor = m.groups[2]!!.value.toInt()
                val patch = m.groups[3]!!.value.toInt()
                // group 4 is the optional commit info
                val numCommits = m.groups[5]?.value?.toInt()
                val hash = m.groups[6]?.value
                Version(major, minor, patch, numCommits, hash)
            }
        }
    }
}
