package com.opsysinc.graphql.plAugin

open class ModelException (msg: String?): RuntimeException(msg) {

}

class AttributeModelException(val attrName: String, val clsName: String, msg: String?)
    : ModelException("$clsName.$attrName $msg")

class ClassModelException(val clsName: String, msg: String?) : ModelException("$clsName: $msg")