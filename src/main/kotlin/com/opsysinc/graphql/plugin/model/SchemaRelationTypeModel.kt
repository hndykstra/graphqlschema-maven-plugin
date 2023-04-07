package com.opsysinc.graphql.plugin.model

import com.opsysinc.graphql.plAugin.AttributeModelException
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
        if (label.size != 1) throw ClassModelException(classInfo.name().toString(),
            "Relation entity requires exactly one label")
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
        throw AttributeModelException(attr.schemaName, classInfo.name().toString(), "Unable to add relationship attribute on relationship entity.")
    }

    override fun resolve(model: SchemaModel) {
        // if fromType and toType are set, we need the actual SchemaTypeModel for them
        val workingToType = toType
        val workingFromType = fromType

        if (workingToType == null) throw ClassModelException(classInfo.name().toString(), "Relation entity does not have @EndNode")
        toModel = model.getTypeModel(workingToType)
        if (toModel == null) throw ClassModelException(classInfo.name().toString(), "Relation entity end node is not a valid entity")

        fromModel = workingFromType?.let { model.getTypeModel(it) } ?: model.findFromTypeModel(this)
        if (fromModel == null) throw ClassModelException(classInfo.name().toString(), "Relation entity not referenced from any source node.")
    }

    override fun generateTypeStatement(): String {
        val base = "type $schemaName"
        val workingFromName = if (fromName == null) "sourceNode" else fromName!!
        val workingToName = toName!!
        val fromClause = ", from: \"$workingFromName\""
        val toClause = ", to: \"$workingToName\""
        return "$base @relation(name: \"${relationName}\"$fromClause$toClause) {"
    }

    override fun generateRelations(): String {
        // no actual relations except the from and to
        val workingFromName = if (fromName == null) "sourceNode" else fromName!!
        val workingToName = toName!!
        val fromAttr = "$INDENT$workingFromName : ${fromModel?.schemaName}!\n"
        val toAttr = "$INDENT$workingToName : ${toModel?.schemaName}!"
        return fromAttr + toAttr
    }
}