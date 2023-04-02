package com.opsysinc.graphql.plugin.model

/**
 * Simple attributes are scalar types or collections thereof
 */
class SimpleAttributeModel (val schemaName: String, val schemaType : GraphQLScalar, val required: Boolean, val collection: Boolean) {

    // TODO: SimpleAttributeModel can actually be wither GraphQLScalar or EnumType

    fun generateAttribute() : String {
        val type = if (collection) "[${schemaType.scalarRepresentation}!]" else schemaType.scalarRepresentation
        return if (required) "$schemaName : $type!" else "$schemaName : $type"
    }
}