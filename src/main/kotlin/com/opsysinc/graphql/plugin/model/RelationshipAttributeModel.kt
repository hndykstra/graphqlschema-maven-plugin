package com.opsysinc.graphql.plugin.model

import org.jboss.jandex.Type

/**
 * Relationship attributes represent a relationship to another schema type, so they must have relation names
 * and directions.
 * @constructor This is constructed with a Type rather than SchemaTypeModel, because the type model may not
 * have been created yet.
 * @property schemaName Name of the attribute to generate in the schema
 * @property schemaType The Jandex Type of the base type of the attribute (i.e., type of the element of collections)
 * @property direction Direction of the Neo4j relationship, OUT or IN.
 * @property required Whether the attribute is required or not in the schema, e.g. schemaName: SchemaType!
 * @property collection Whether the attribute is a collection type in the schema, e.g. schemaName: [SchemaType]
 */
class RelationshipAttributeModel (val schemaName: String, val schemaType: Type,
                                  val relationshipName: String, val direction: RelationDirection, val required: Boolean, val collection: Boolean) {
    var schemaTypeName: String? = null

    fun resolve(model: SchemaModel, owningType: SchemaTypeModel) {
        val schemaTypeModel = model.getTypeModel(schemaType)
            ?: throw IllegalArgumentException("Attribute '$schemaName' of class '${owningType.schemaName}' could not resolve type ${schemaType.name()}")
        schemaTypeName = schemaTypeModel.schemaName
    }

    fun generateAttribute() : String {
        val type = if (collection) "[$schemaTypeName!]" else schemaTypeName
        val base = if (required) "$schemaName : $type!" else "$schemaName : $type"
        // @relation(name: "USES_DOCUMENT_CLASS", direction: OUT)
        val relation = " @relation(name: \"$relationshipName\", direction: \"${direction.name}\")"
        return base + relation
    }
}