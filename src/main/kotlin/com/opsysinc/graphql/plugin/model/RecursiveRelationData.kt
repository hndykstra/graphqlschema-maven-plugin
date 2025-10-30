package com.opsysinc.graphql.plugin.model

/**
 * Represents a relationship that may recursively incorporate
 * deeper relationships
 */
class RecursiveRelationData(
    val isRecursive: Boolean,
    val relationName: String,
    val relation: RelationshipAttributeModel,
    val relatedType: SchemaTypeModel
    ) {

    private val includedRelationsAdded = mutableListOf<RecursiveRelationData>()

    val includedRelations: List<RecursiveRelationData>
        get() = includedRelationsAdded.toList()

    fun add(recursiveRelation: RecursiveRelationData) {
        includedRelationsAdded.add(recursiveRelation)
    }

    fun buildFragments(list: MutableSet<String>) {
        if (isRecursive) {
            list.add(relatedType.fragmentName())
            includedRelationsAdded.forEach {
                it.buildFragments(list)
            }
        }
    }
}