package com.opsysinc.graphql.plugin.model

import com.artisanecm.graphql.repository.nodeentity.NodeEntity
import org.jboss.jandex.AnnotationInstance
import org.jboss.jandex.ClassInfo

open class SchemaTypeModel (val classInfo: ClassInfo, val overrideName : String? = null) {
    companion object {
        const val INDENT = "    "
    }

    val schemaName : String
    val nodeEntity: AnnotationInstance?
    val simpleAttributes =  mutableListOf<SimpleAttributeModel>()
    val relationshipAttributes = mutableListOf<RelationshipAttributeModel>()
    val interfaces = mutableListOf<SchemaTypeModel>()
    val ignoreAttrs = mutableListOf<String>()
    val key = mutableListOf<SimpleAttributeModel>()

    init {
        // node entity is not required to create a model if it is a non-entity base class
        nodeEntity = classInfo.annotation(NodeEntity::class.java)

        val label = nodeEntity?.value("label")?.asStringArray()
        schemaName = overrideName ?:
            if (label == null || label.size == 0) classInfo.simpleName()
            else label[0]
    }

    fun ignoreAttribute(attrName: String) {
        ignoreAttrs.add(attrName)
        simpleAttributes.removeIf { it.schemaName == attrName }
        relationshipAttributes.removeIf { it.schemaName == attrName }
    }

    open fun addSimpleAttribute(attr: SimpleAttributeModel) : Boolean {
        return !ignoreAttrs.contains(attr.schemaName)
                && !simpleAttributes.any { it.schemaName == attr.schemaName }
                && simpleAttributes.add(attr)
    }

    open fun addKeyAttribute(attr: SimpleAttributeModel) : Boolean {
        return key.add(attr)
    }

    open fun addRelationshipAttribute(attr: RelationshipAttributeModel) : Boolean {
        return !ignoreAttrs.contains(attr.schemaName)
                && !relationshipAttributes.any { it.schemaName == attr.schemaName}
                && relationshipAttributes.add(attr)
    }

    fun addInterface(iface: SchemaTypeModel) {
        interfaces.find { it.schemaName == iface.schemaName}
            ?: interfaces.add(iface)
    }

    open fun resolve(model: SchemaModel) {
        relationshipAttributes.forEach { attr -> attr.resolve(model, this) }
    }

    fun generate() : String {
        // defer to other methods so the @relation bits are added by subclass if necessary
        return generateTypeStatement() + "\n" +
                generateAttributes() + "\n" +
                generateRelations() + "\n" +
                generateEndStatement()
    }

    fun generateFragment() : String {
        val attrs = simpleAttributes.asSequence()
            .map { INDENT + it.schemaName }
            .joinToString("\n")
        val fragmentName = fragmentName()
        return "fragment ${fragmentName} on $schemaName {" + "\n" + attrs + "\n" + "}"
    }

    open fun generateTypeStatement() : String {
        if (interfaces.isNotEmpty()) {
            val interfaceNames = interfaces.asSequence()
                .map { it.schemaName }
                .joinToString(" & ")
            return "type $schemaName implements $interfaceNames {"
        } else {
            return "type $schemaName {"
        }
    }

    open fun generateAttributes() : String {
        return simpleAttributes.asSequence()
            .map { INDENT + it.generateAttribute() }
            .joinToString("\n")
    }

    open fun generateRelations() : String {
        return relationshipAttributes.asSequence()
            .map { INDENT + it.generateAttribute() }
            .joinToString("\n")
    }

    open fun generateEndStatement() : String {
        return "}"
    }

    open fun fragmentName() : String {
        return "${schemaName}Fields"
    }
}