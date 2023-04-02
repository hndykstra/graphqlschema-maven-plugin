package com.opsysinc.graphql.plugin.model

class EnumType (schemaName: String, val allowedValues: List<String>) : GraphQLScalar(schemaName){
    companion object {
        const val INDENT = "    "
    }

    fun generate() : String {
        val values = allowedValues.asSequence()
            .map { INDENT + it }
            .joinToString("\n")
        return "enum ${scalarRepresentation} {\n$values\n}"
    }
}