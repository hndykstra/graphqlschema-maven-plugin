package com.opsysinc.graphql.plugin.model

import org.jboss.jandex.ClassInfo

class InterfaceTypeModel (classInfo: ClassInfo, schemaName: String) : SchemaTypeModel(classInfo, schemaName) {

    override fun generateTypeStatement(): String {
        return "interface $schemaName} {"
    }
}