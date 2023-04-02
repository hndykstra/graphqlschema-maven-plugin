package com.opsysinc.graphql.plugin.model

import com.opsysinc.graphql.plAugin.ClassModelException
import org.jboss.jandex.ClassInfo
import org.jboss.jandex.Type

class SchemaRelationTypeModel (classInfo: ClassInfo): SchemaTypeModel(classInfo, classInfo.simpleName()) {
    var fromType : Type? = null
    var fromModel : SchemaTypeModel? = null
    var fromName : String? = null
    var toType : Type? = null
    var toModel : SchemaTypeModel? = null
    var toName : String? = null
    val relationName : String

    init {
        // read relationship info from the NodeEntity annotation
        val label = nodeEntity.value("label").asStringArray()
        if (label.size != 1) throw IllegalArgumentException("Relation entity requires exactly one label")
        relationName = label[0]
    }

    /**
     * Invoked when a @StartNode is detected.
     * May not happen if it is missing, in which case we'd need to resolve it
     * by finding a source entity that contains such an item.
     */
    fun setFrom(attrName: String, type: Type) {
        fromType = type
        fromName = attrName
        // ensure that this isn't treated as an attribute
        simpleAttributes.removeIf { it.schemaName == attrName }
    }

    fun setTo(attrName: String, type: Type) {
        toType = type
        toName = attrName
        simpleAttributes.removeIf { it.schemaName == attrName }
    }

    override fun addSimpleAttribute(attr: SimpleAttributeModel): Boolean {
        return attr.schemaName != fromName
                && attr.schemaName != toName
                &&  super.addSimpleAttribute(attr)
    }

    override fun addRelationshipAttribute(attr: RelationshipAttributeModel): Boolean {
        throw IllegalArgumentException("${classInfo.name()}: Unable to add relationship attribute ${attr.schemaName} on relationship.")
    }

    override fun resolve(model: SchemaModel) {
        // if fromType and toType are set, we need the actual SchemaTypeModel for them
        val workingToType = toType
        val workingFromType = fromType
        if (workingToType == null) throw ClassModelException(classInfo.name().toString(), "Relation entity does not have @EndNode")

        toModel = model.getTypeModel(workingToType)
        if (toModel == null) throw ClassModelException(classInfo.name().toString(), "Relation entity end node is not a valid entity")

        fromModel = if (workingFromType == null) model.findFromTypeModel(this) else model.getTypeModel(workingFromType)
        if (fromModel == null) throw ClassModelException(classInfo.name().toString(), "Relation entity could not resolve start node. Missing @StartNode and no related entity was found.")
    }

    override fun generateTypeStatement(): String {
        val base = "type $schemaName"
        val workingFromName = if (fromName == null) "source" else fromName!!
        val workingToName = toName!!
        return "$base @relation(name: \"${relationName}\", from: \"$workingFromName}\", to: \"${workingToName}\") {"
    }

    override fun generateRelations(): String {
        // no actual relations except the from and to
        val workingFromName = if (fromName == null) "source" else fromName!!
        val workingToName = toName!!
        val fromAttr = "$INDENT$workingFromName ${fromModel?.schemaName}!"
        val toAttr = "$INDENT$workingToName ${toModel?.schemaName}!"
        return fromAttr + "\n" + toAttr
    }
}