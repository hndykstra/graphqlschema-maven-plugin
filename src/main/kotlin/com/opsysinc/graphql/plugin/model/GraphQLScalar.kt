package com.opsysinc.graphql.plugin.model

/**
 * Because we can declare scalar types in the schema, we have an interface.
 * This can represent
 */
open class GraphQLScalar (val scalarRepresentation: String) {
    companion object {
        val GraphQLInt = GraphQLScalar("Int")
        val GraphQLString = GraphQLScalar("String")
        val GraphQLFloat = GraphQLScalar("Float")
        val GraphQLBoolean = GraphQLScalar("Boolean")
        val GraphQLID = GraphQLScalar("ID")
    }

    override fun equals(other: Any?) : Boolean {
        return other is GraphQLScalar && this.scalarRepresentation == other.scalarRepresentation
    }

    override fun hashCode(): Int {
        return scalarRepresentation.hashCode()
    }
}