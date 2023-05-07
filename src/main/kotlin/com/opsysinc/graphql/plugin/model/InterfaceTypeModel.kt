package com.opsysinc.graphql.plugin.model

import org.jboss.jandex.ClassInfo

class InterfaceTypeModel (classInfo: ClassInfo, schemaName: String) : SchemaTypeModel(classInfo, schemaName) {

    override fun generateTypeStatement(): String {
        return "interface $schemaName {"
    }

    override fun fragmentName() : String {
        val baseName = if (schemaName.endsWith(SchemaModel.ABSTRACT_INTERFACE_SUFFIX))
            schemaName.substring(0..schemaName.length - SchemaModel.ABSTRACT_INTERFACE_SUFFIX.length -1)
        else
            schemaName
        return "${baseName}Fields"
    }
}