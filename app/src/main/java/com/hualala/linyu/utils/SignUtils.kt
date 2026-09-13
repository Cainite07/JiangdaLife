package com.hualala.linyu.utils

import java.security.MessageDigest

/** Generates the compatibility signature required by the device login service. */
object SignUtils {
    fun sign(telephone: String): String {
        require(telephone.length == 11) { "手机号必须为11位" }
        val raw = telephone.substring(0, 3) + telephone.substring(7, 11) + "klcx"
        return md5(raw)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
