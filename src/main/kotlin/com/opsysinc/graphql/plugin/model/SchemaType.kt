package com.opsysinc.graphql.plugin.model

import org.jboss.jandex.Type

/**
 * Encapsulates a raw type encountered as field or method type
 * (or otherwise) with the resolved element type (if it is an array
 * or parameterized type
 */
class SchemaType (val rawType: Type, val elementType: Type) {
    override fun toString(): String {
        return elementType.name().toString()
    }
}