package com.opsysinc.graphql.plugin

object Util {

    fun pluralize(input: String) : String {
        if (input.endsWith("ay") || input.endsWith("ey") || input.endsWith("iy") || input.endsWith("oy") || input.endsWith("uy") || input.endsWith("yy")) {
            return input + "s"
        }
        if (input.endsWith("y")) {
            return input.substring(0, input.length - 1) + "ies"
        }
        return if (input.endsWith("s") || input.endsWith("sh") || input.endsWith("ch") || input.endsWith("x") || input.endsWith("z")) {
            input + "es"
        } else input + "s"
    }

    fun decapitalize(input: String?) : String? {
        return input?.substring(0, 1)?.lowercase() + input?.substring(1) ?: input
    }
}