package com.opsysinc.graphql.plugin

object Util {

    fun pluralize(input: String) : String {
        return if (input.endsWith("ay") || input.endsWith("ey") || input.endsWith("iy") || input.endsWith("oy") || input.endsWith("uy") || input.endsWith("yy"))
            return input + "s"
        else if (input.endsWith("y"))
            return input.dropLast(1) + "ies"
        else if (input.endsWith("ss") || input.endsWith("sh") || input.endsWith("ch") || input.endsWith("x") || input.endsWith("z"))
            input + "es"
        else if (input.endsWith("s"))
            input
        else
            input + "s"
    }

    fun decapitalize(input: String?) : String? {
        return if (input != null)
            input.substring(0, 1).lowercase() + input.substring(1)
        else
            null
    }

    fun javaScalarRepresentation(input: String) : String {
        return when (input) {
            "ID" -> "String"
            "String" -> "String"
            "Boolean" -> "Boolean"
            "Int" -> "Integer"
            "Float" -> "Float"
            else -> input
        }
    }

    fun kotlinScalarRepresentation(input: String) : String {
        return input
    }
}