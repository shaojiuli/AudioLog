package com.audiostats.data

object Attribution {
    const val LEGACY = "legacy"
    const val REPORTED = "reported"
    const val QQ_COMPAT = "qq_compat"
    const val UNCONFIRMED = "unconfirmed"
    const val MANUAL = "manual"
    const val UNKNOWN_PKG = "unconfirmed"

    fun parse(value: String?) = value?.takeIf {
        it in setOf(LEGACY, REPORTED, QQ_COMPAT, UNCONFIRMED, MANUAL)
    } ?: LEGACY
}
