package com.opsysinc.graphql.plugin

import com.artisanecm.graphql.repository.nodeentity.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.opsysinc.graphql.plAugin.AttributeModelException
import com.opsysinc.graphql.plAugin.ClassModelException
import com.opsysinc.graphql.plAugin.ModelException
import com.opsysinc.graphql.plugin.model.*
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugin.logging.Log
import org.jboss.jandex.*
import org.jboss.jandex.AnnotationTarget
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.util.*
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.declaredMemberProperties

/**
 * Constructed with the index, mutable schema model, and class loader.
 * The schema model should be initialized with all defaults plus any
 * additional user-specified information, such as scalar mappings, etc.
 * Execution of the schema generation will scan the index and add the
 * discovered schema elements.
 */
class SchemaGeneration(
    val log: Log,
    private val index: IndexView,
    val schemaModel: SchemaModel,
    private val projectClassLoader: ClassLoader
) {
    enum class ScanLocation {
        METHODS, FIELDS
    }

    private val errors = mutableListOf<ModelException>()

    /**
     * This may throw on fatal errors. If it returns, then the model will be updated
     * with as much information as could be scanned, but the error list will show
     * items that failed. Caller can decide how to handle this, e.g., whether to write out
     * the data, log errors, throw final exception, etc.
     */
    fun scan() : List<ModelException> {
        log.info("Begin scan index")
        // this is in order because some scans depend on other scans being done
        scanEnums()
        scanInterfaces()
        scanTypes()
        return errors
    }

    /**
     * Scan for entity types in the index.
     */
    private fun scanTypes() {
        index.getAnnotations(NodeEntity::class.java).forEach { annotation ->
            val cls = annotation.target().asClass()!!
            log.debug("Scan @NodeEntity ${cls.name()}")
            try {
                if (cls.isInterface) throw ClassModelException(cls.name().toString(), "@NodeEntity is only supported on classes")
                val entityType = annotation.value("type")?.asEnum()?.let { NodeEntityType.valueOf(it) } ?: NodeEntityType.NODE
                val superClsName = cls.superName()
                val addlInterfaces = if (superClsName != null)
                    scanSuper(superClsName, cls.name(), entityType)
                else
                    emptyList()

                val model = when (entityType) {
                    NodeEntityType.NODE -> buildNodeEntityModel(cls)
                    NodeEntityType.RELATION -> buildRelationEntityModel(cls)
                }
                model?.let {
                    addlInterfaces.forEach(model::addInterface)
                    schemaModel.addType(model)
                }
           } catch (e: ModelException) {
                errors.add(e)
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("Problem with annotations on ${cls.name()}", e)
            }
        }
    }
    
    private fun buildNodeEntityModel (cls : ClassInfo) : SchemaTypeModel? {
        val model = SchemaTypeModel(cls)
        scanTypeForInterfaces(cls, model)
        val scanLocation = determineScanLocation(cls, NodeKey::class.java)
        val errs = if (scanLocation == ScanLocation.METHODS) {
            scanTypeGetters(cls, model, false)
            scanTypeFields(cls, model, true)
        } else {
            scanTypeFields(cls, model, false)
            scanTypeGetters(cls, model, true)
        }
        return if (errs.isEmpty()) model else null
    }
    
    private fun buildRelationEntityModel(cls: ClassInfo) : SchemaTypeModel? {
        val model = SchemaRelationTypeModel(cls)
        val ifaceErrs = scanTypeForInterfaces(cls, model)
        errors.addAll(ifaceErrs)
        // this uses both getters and fields to identify schema properties
        val scanLocation = determineScanLocation(cls, EndNode::class.java)
        val errs = if (scanLocation == ScanLocation.METHODS) {
            // This will prioritize getters but also pick up fields if specifically annotated @NodeAttribute
            scanTypeGetters(cls, model, false)
            scanTypeFields(cls, model, true)
        } else {

            scanTypeFields(cls, model, false)
            scanTypeGetters(cls, model, true)
        }
        return if (errs.isEmpty()) model else null
    }

    /**
     * Scans super types, return a list specifically of non-ignored, non-entity
     * superclasses as InterfaceTypeModel.
     * These will generate interfaces and be added as interface types for the schema
     */
    private fun scanSuper(superName: DotName, subClassName: DotName, subClassType: NodeEntityType) : Collection<InterfaceTypeModel> {
        // this will scan a super type which is referenced. It may or may not be
        // a NodeEntity; it may or may not be in the index view; it may or may not
        // have already been scanned.
        val interfacesDiscovered = mutableListOf<InterfaceTypeModel>()
        val superCls = index.getClassByName(superName)
        val nextSuperName = superCls?.superName()
        if (nextSuperName != null)
            interfacesDiscovered.addAll(scanSuper(nextSuperName, superName, subClassType))
        val hasScanned = schemaModel.hasTypeModel(superName)
        if (!hasScanned && superCls != null && !superCls.javaClass.equals(java.lang.Object::class.java)) {

            // super class found in index and it is a real class to analyze
            val ignore = superCls.annotation(NodeIgnore::class.java)
            val entity = superCls.annotation(NodeEntity::class.java)
            if (ignore == null) {
                val entityType = entity?.value("type")?.asEnum()?.let { NodeEntityType.valueOf(it) } ?: subClassType
                if (entityType != subClassType)
                    throw ClassModelException(superName.toString(), "Entity type $entityType does not match subclass $subClassName.")
                if (entity != null) {
                    val model = when (entityType) {
                        NodeEntityType.NODE -> buildNodeEntityModel(superCls)
                        NodeEntityType.RELATION -> buildRelationEntityModel(superCls)
                    }
                    model?.let { schemaModel.addType(model) }
                } else {
                    try {
                        val model = buildInterfaceModel(superCls, null)
                        schemaModel.addInterface(model)
                        interfacesDiscovered.add(model)
                    } catch (err : ModelException) {
                        errors.add(err)
                    }
                }
            }
        } else {
            schemaModel.getInterface(superName)?.let { interfacesDiscovered.add(it) }
        }
        return interfacesDiscovered
    }

    private fun scanInterfaces() {
        index.getAnnotations(SchemaInterface::class.java).forEach { annotation ->
            try {
                val cls = annotation.target().asClass()!!
                log.debug("Scan @SchemaInterface ${cls.name()}")
                if (!cls.isInterface) throw ClassModelException(cls.name().toString(),
                    "@SchemaInterface is only supported on interfaces")
                val annotationName = annotation.value("schemaName")?.asString()
                val workingName = if (annotationName != null && annotationName.trim().isNotEmpty()) annotationName else cls.simpleName()
                val model = buildInterfaceModel(cls, workingName)
                schemaModel.addInterface(model)
            } catch (e: ModelException) {
                // we will skip this but add errors
                errors.add(e)
            }
        }
    }

    /**
     * This represents a GraphQL schema interface, which could be either a Java
     * interface implemented by the entity class, or a non-entity superclass,
     * i.e., superclass of the entity class that does not itself have @NodeEntity
     */
    private fun buildInterfaceModel(cls: ClassInfo, schemaName: String?) : InterfaceTypeModel {
        val workingName = if (schemaName != null && schemaName.isNotBlank())
            schemaName
        else
            "${cls.simpleName()}${SchemaModel.ABSTRACT_INTERFACE_SUFFIX}"
        val model = InterfaceTypeModel(cls, workingName)
        if (!cls.isInterface) {
            scanTypeFields(cls, model, false)
        }
        scanInterfaceGetters(cls, model)
        return model
    }

    private fun scanEnums() {
        // this will only find enums that have @SchemaEnum, which isn't necessarily all of them
        // more of pre-warming the model than necessity
        // this ensures that enum types can have a schemaName assigned in the annotation
        index.getAnnotations(SchemaEnum::class.java).forEach { annotation ->
            try {
                // this throws or returns non-null
                val cls = annotation.target().asClass()!!
                log.debug("Process @SchemaEnum ${cls.name()}")
                if (!schemaModel.hasEnum(cls.name())) {
                    schemaModel.addEnumType(cls.name(), createEnumFromClass(annotation, cls))
                }
            } catch (e: ModelException) {
                // somehow this got on something that is not a class
                // skip it but add to errors
                errors.add(e)
            }
        }
    }

    private fun scanInterfaceGetters(cls: ClassInfo, typeModel: SchemaTypeModel) : List<ModelException> {
        val errs = mutableListOf<ModelException>()
        // if this is a non-entity class instead of interface, the getters might
        // override field scan, so they will require @NodeAttribute
        errs.addAll(scanDeclaredGetters(cls, typeModel, !cls.isInterface))
        val sup = cls.interfaceNames()
        sup.forEach {
            val supCls = index.getClassByName(it)
            if (supCls != null) {
                // super interface is in the index, so scan it too
                // Q: should we check for NodeInterface annotation on super?
                // I say no because then both interfaces necessarily appear in the schema
                // which may not be what is wanted.
                // rarely, other interfaces like Serializable etc. but we skip those.
                errs.addAll(scanInterfaceGetters(supCls, typeModel))
            }
        }
        return errs
    }

    /**
     * Return identified errors - attributes found but for some reason could not be processed.
     * This is returns as attrName -> Exception
     * If a same-named attribute is successfully added in another part of the scan, suppress the error
     */
    private fun scanTypeGetters(cls: ClassInfo,
                                typeModel: SchemaTypeModel,
                                requireNodeAttibute: Boolean) : List<ModelException> {
        val errs = mutableListOf<ModelException> ()
        errs.addAll(scanDeclaredGetters(cls, typeModel, requireNodeAttibute))
        val sup = cls.superClassType()
        val supInIndex = index.getClassByName(sup.name())
        if (supInIndex != null) {
            // Q: Should we allow @NodeIgnore on superclasses?
            errs.addAll(scanTypeGetters(supInIndex, typeModel, requireNodeAttibute))
        }
        return errs
    }

    private fun scanTypeForInterfaces(cls: ClassInfo, typeModel: SchemaTypeModel) : List<ModelException> {
        val ifNameList = allInterfaceNames(cls)
        ifNameList.forEach { ifName ->
            // assume that all schema interfaces are scanned already because interface scanning is before types
            schemaModel.getInterface(ifName)?.let {
                typeModel.addInterface(it)
            }
        }
        return emptyList()
    }

    private fun allInterfaceNames(cls: ClassInfo) : Set<DotName> {
        val ifList = mutableSetOf<DotName>()
        cls.interfaceNames().forEach { ifName -> ifList.add(ifName) }
        val superName = cls.superName()
        val superCls = if (superName != null) index.getClassByName(superName) else null
        if (superCls != null)
            ifList.addAll(allInterfaceNames(superCls))
        return ifList
    }

    private fun scanDeclaredGetters(cls: ClassInfo,
                                    typeModel: SchemaTypeModel,
                                    requireNodeAttribute: Boolean) : List<ModelException> {
        val errs = mutableListOf<ModelException>()
        // only public, non-static methods getXxx() or isXxx() without @NodeIgnore
        cls.methods().asSequence()
            .filter { m -> ((m.flags().toInt()) and (Modifier.STATIC)) == 0 }
            .filter { m -> ((m.flags().toInt()) and (Modifier.PUBLIC)) != 0 }
            .filter { m -> m.parametersCount() == 0 && (m.name().startsWith("get") || m.name().startsWith("is")) }
            .filter { m -> !requireNodeAttribute || m.hasAnnotation(NodeAttribute::class.java) }
            .forEach { m ->
                    // TODO: simplify this. Maybe move some logic to shorter methods and scan the types, scalars, enums, etc
                    // up front to select the right approach with less nested ifs
                    val startNode = m.annotation(StartNode::class.java)
                    val endNode = m.annotation(EndNode::class.java)
                    val ignoreIt = m.hasAnnotation(NodeIgnore::class.java)
                    if (startNode == null && endNode == null) {
                        try {
                            val key = m.annotation(NodeKey::class.java)
                            val type = m.returnType()
                            val scalarType = getSchemaScalar(type)
                            if (scalarType != null) {
                                val scalarModel = createSimpleAttributeForGetter(m, scalarType)
                                if (ignoreIt) {
                                    typeModel.ignoreAttribute(scalarModel.schemaName)
                                } else {
                                    typeModel.addSimpleAttribute(scalarModel)
                                    if (key != null)
                                        typeModel.addKeyAttribute(scalarModel)
                                }
                            } else {
                                val enumType = extractEnumType(type)
                                if (enumType != null) {
                                    var modelEnum = schemaModel.getEnum(enumType.name())
                                    if (modelEnum == null) {
                                        // TODO: maybe there should be a strict mode for this
                                        modelEnum = createEnumFromType(enumType.asClassType())
                                        logger.info("Enum ${enumType.name()} added to model without @SchemaEnum annotation")
                                        schemaModel.addEnumType(enumType.name(), modelEnum)
                                    }
                                    val enumModel = createSimpleAttributeForGetter(m, modelEnum)
                                    if (ignoreIt) {
                                        typeModel.ignoreAttribute(enumModel.schemaName)
                                    } else {
                                        typeModel.addSimpleAttribute(enumModel)
                                        if (key != null)
                                            typeModel.addKeyAttribute(enumModel)
                                    }
                                } else {
                                    val relModel = createRelationshipAttributeForGetter(m)
                                    if (ignoreIt) {
                                        schemaModel.ignoreMention(
                                            relModel.schemaType.name(),
                                            m.declaringClass(),
                                            relModel.schemaName
                                        )
                                        typeModel.ignoreAttribute(relModel.schemaName)
                                    } else {
                                        schemaModel.addMention(
                                            relModel.schemaType.name(),
                                            m.declaringClass(),
                                            relModel.schemaName
                                        )
                                        typeModel.addRelationshipAttribute(relModel)
                                    }
                                }
                            }
                        } catch (e: ModelException) {
                            // throw a new one with better contextual information
                            if (e !is AttributeModelException) {
                                val ie = AttributeModelException(m.name(), cls.name().toString(), e.message)
                                ie.addSuppressed(e)
                                errs.add(ie)
                            } else {
                                errs.add(e)
                            }
                        }
                    } else if (startNode != null) {
                        if (typeModel is SchemaRelationTypeModel) {
                            typeModel.setFrom(attrNameFromMethod(m), m.returnType())
                        } else {
                            errs.add(
                                AttributeModelException(
                                    m.name(), cls.name().toString(),
                                    "Encountered @StartNode in non-relation node entity"
                                )
                            )
                        }
                    } else if (endNode != null) {
                        if (typeModel is SchemaRelationTypeModel) {
                            typeModel.setTo(attrNameFromMethod(m), m.returnType())
                        } else {
                            errs.add(
                                AttributeModelException(
                                    m.name(), cls.name().toString(),
                                    "Encountered @EndNode in non-relation node entity"
                                )
                            )
                        }
                    }
            }
        return errs
    }

    /**
     * Takes the scalar type (already resolved from getter type)
     */
    private fun createSimpleAttributeForGetter(getter: MethodInfo, scalarType: GraphQLScalar): SimpleAttributeModel {
        val name = attrNameFromMethod(getter)
        val type = getter.returnType()
        val collection = type.kind() == Type.Kind.ARRAY
                || (type.kind() == Type.Kind.PARAMETERIZED_TYPE && isCollectionType(type.asParameterizedType()))
        val required = isRequiredType(getter, type)
        return SimpleAttributeModel(name, scalarType, required, collection)
    }

    /**
     * Takes the scalar type (already resolved from getter type)
     */
    private fun createSimpleAttributeForGetter(getter: MethodInfo, enumType: EnumType): SimpleAttributeModel {
        val name = attrNameFromMethod(getter)
        val type = getter.returnType()
        val collection = type.kind() == Type.Kind.ARRAY
                || (type.kind() == Type.Kind.PARAMETERIZED_TYPE && isCollectionType(type.asParameterizedType()))
        val required = isRequiredType(getter, type)
        return SimpleAttributeModel(name, enumType, required, collection)
    }

    private fun createRelationshipAttributeForGetter(getter: MethodInfo): RelationshipAttributeModel {
        val name = attrNameFromMethod(getter)
        val type = getter.returnType()
        val collection = type.kind() == Type.Kind.ARRAY
                || (type.kind() == Type.Kind.PARAMETERIZED_TYPE && isCollectionType(type.asParameterizedType()))
        val required = isRequiredType(getter, type)
        val na = getter.annotation(NodeAttribute::class.java)
        val elementType = getElementType(type)
        val relatedEntityRelationship = relationNameFromElement(elementType)
        val acrossRelationEntity = relatedEntityRelationship != null

        val relationName = relatedEntityRelationship
            ?: na?.value("relation")?.asString()
            ?: throw AttributeModelException(getter.name(), getter.declaringClass().name().toString(),
                "Non-scalar property requires @NodeAttribute with relationship."
            )
        val direction = na?.value("direction")?.asString() ?: "OUT"
        val cascades = na?.value("cascade")?.asEnumArray() ?: arrayOf<String>()
        return RelationshipAttributeModel(name, elementType, relationName,
            RelationDirection.valueOf(direction),
            acrossRelationEntity, required, collection, cascades)
    }

    private fun scanTypeFields(cls: ClassInfo,
                               typeModel: SchemaTypeModel,
                               requireNodeAttribute: Boolean) : List<ModelException> {
        val errs = mutableListOf<ModelException>()
        errs.addAll(scanDeclaredFields(cls, typeModel, requireNodeAttribute))
        val sup = cls.superClassType()
        val supInIndex = index.getClassByName(sup.name())
        if (supInIndex != null) {
            errs.addAll(scanTypeFields(supInIndex, typeModel, requireNodeAttribute))
        }
        return errs
    }

    private fun scanDeclaredFields(cls: ClassInfo,
                                   typeModel: SchemaTypeModel,
                                   requireNodeAttribute: Boolean) : List<ModelException> {
        val errs = mutableListOf<ModelException>()
        // only non-static fields without @NodeIgnore
        cls.fields().asSequence()
            .filter { field -> ((field.flags().toInt()) and (Modifier.STATIC)) == 0 }
            .filter { field -> !field.hasAnnotation(NodeIgnore::class.java) }
            .filter { field -> !requireNodeAttribute || field.hasAnnotation(NodeAttribute::class.java) }
            .forEach { field ->
                try {
                    val startNode = field.annotation(StartNode::class.java)
                    val endNode = field.annotation(EndNode::class.java)
                    val ignoreIt = field.hasAnnotation(NodeIgnore::class.java)
                    if (startNode == null && endNode == null) {
                        val key = field.annotation(NodeKey::class.java)
                        val type = field.type()
                        val scalarType = getSchemaScalar(type)
                        if (scalarType != null) {
                            val scalarModel = createSimpleAttributeForField(field, scalarType)
                            if (ignoreIt) {
                                typeModel.ignoreAttribute(scalarModel.schemaName)
                            } else {
                                typeModel.addSimpleAttribute(scalarModel)
                                if (key != null)
                                    typeModel.addKeyAttribute(scalarModel)
                            }
                        } else {
                            val enumType = extractEnumType(type)
                            if (enumType != null) {
                                var modelEnum = schemaModel.getEnum(enumType.name())
                                if (modelEnum == null) {
                                    // TODO: maybe there should be a strict mode for this
                                    modelEnum = createEnumFromType(enumType.asClassType())
                                    logger.warn("Enum ${enumType.name()} added to model without @SchemaEnum annotation")
                                    schemaModel.addEnumType(enumType.name(), modelEnum)
                                }
                                val enumModel = createSimpleAttributeForField(field, modelEnum)
                                if (ignoreIt) {
                                    typeModel.ignoreAttribute(enumModel.schemaName)
                                } else {
                                    typeModel.addSimpleAttribute(enumModel)
                                    if (key != null)
                                        typeModel.addKeyAttribute(enumModel)
                                }
                            } else {
                                val relModel = createRelationshipAttributeForField(field)
                                if (ignoreIt) {
                                    schemaModel.ignoreMention(
                                        relModel.schemaType,
                                        field.declaringClass(),
                                        relModel.schemaName
                                    )
                                } else {
                                    schemaModel.addMention(
                                        relModel.schemaType,
                                        field.declaringClass(),
                                        relModel.schemaName
                                    )
                                    typeModel.addRelationshipAttribute(relModel)
                                }
                            }
                        }
                    } else if (startNode != null) {
                        if (typeModel is SchemaRelationTypeModel) {
                            typeModel.setFrom(attrNameFromField(field), field.type())
                        } else {
                            throw AttributeModelException(
                                field.name(), cls.name().toString(),
                                "Encountered @StartNode in non-relation node entity"
                            )
                        }
                    } else if (endNode != null) {
                        if (typeModel is SchemaRelationTypeModel) {
                            typeModel.setTo(attrNameFromField(field), field.type())
                        } else {
                            throw AttributeModelException(
                                field.name().toString(), cls.name().toString(),
                                "Encountered @EndNode in non-relation node entity"
                            )
                        }
                    }
                } catch (me: ModelException) {
                    errs.add(me)
                } catch (e: RuntimeException) {
                    // generally something about the field type was not supported
                    // throw a new one with better contextual information
                    val ie = AttributeModelException(field.name().toString(), cls.name().toString(),
                        "${e.javaClass.name}: ${e.message}")
                    ie.addSuppressed(e)
                    errs.add(ie)
                }
            }
        return errs
    }

    private fun createSimpleAttributeForField(field: FieldInfo, scalarType: GraphQLScalar): SimpleAttributeModel {
        val name = attrNameFromField(field)
        val type = field.type()
        val collection = type.kind() == Type.Kind.ARRAY
                || (type.kind() == Type.Kind.PARAMETERIZED_TYPE && isCollectionType(type.asParameterizedType()))
        val required = isRequiredType(field, type)
        return SimpleAttributeModel(name, scalarType, required, collection)
    }

    private fun createRelationshipAttributeForField(field: FieldInfo): RelationshipAttributeModel {
        val name = attrNameFromField(field)
        val type = field.type()
        val collection = type.kind() == Type.Kind.ARRAY
                || (type.kind() == Type.Kind.PARAMETERIZED_TYPE && isCollectionType(type.asParameterizedType()))
        val required = isRequiredType(field, type)
        val elementType = getElementType(type)
        val na = field.annotation(NodeAttribute::class.java)
        val relatedEntityRelationship = relationNameFromElement(elementType)
        val acrossRelationEntity = relatedEntityRelationship != null

        val relationName = relatedEntityRelationship
            ?: na?.value("relation")?.asString()
            ?: throw AttributeModelException(field.name(), field.declaringClass().name().toString(),
                "Non-scalar property '$name' requires @NodeAttribute with relationship.")
        val direction = na?.value("direction")?.asString()
        val cascades = na?.value("cascade")?.asEnumArray() ?: arrayOf<String>()
        return RelationshipAttributeModel(name, elementType, relationName, RelationDirection.valueOf(direction ?: "OUT"),
            acrossRelationEntity, required, collection, cascades)
    }

    private fun createEnumFromType(enumType: ClassType) : EnumType {
        try {
            val actualClass = classFromName(enumType.name(), true)
            val name = actualClass.simpleName
            val values = actualClass.enumConstants.map { (it as Enum<*>).name }
            return EnumType(name, values)
        } catch (e: ClassNotFoundException) {
            throw MojoFailureException("Unresolvable reference to enum ${enumType.name()}", e)
        }
    }

    private fun createEnumFromClass(annotation: AnnotationInstance, enumClass: ClassInfo): EnumType {
        val annotationName = annotation.value("schemaName")?.asString()
        val name = if (annotationName != null && annotationName.trim().isNotEmpty()) annotationName
            else enumClass.simpleName()

        try {
            val actualClass = classFromName(enumClass.name(), true)
            val values = actualClass.enumConstants.map { (it as Enum<*>).name }
            return EnumType(name, values)
        } catch (e: ClassNotFoundException) {
            throw MojoFailureException("Unresolvable reference to enum ${enumClass.name()}", e)
        }
    }

    private fun isRequiredType(getter: MethodInfo, type: Type): Boolean {
        // true if primitive, has @NodeKey, @NodeAttribute(required=true)
        // kotlin type and not kotlin isMarkedNullable
        if (type.kind() == Type.Kind.PRIMITIVE) return true

        val nk = getter.annotation(NodeKey::class.java)
        if (nk != null) return true

        val na = getter.annotation(NodeAttribute::class.java)
        return (na?.value("required")?.asBoolean() ?: false)
                || isKotlinRequired(getter)
    }

    private fun isRequiredType(field: FieldInfo, type: Type): Boolean {
        // true if primitive, has @NodeKey, @NodeAttribute(required=true)
        // kotlin type and not kotlin isMarkedNullable might help
        if (type.kind() == Type.Kind.PRIMITIVE) return true

        val nk = field.annotation(NodeKey::class.java)
        if (nk != null) return true

        val na = field.annotation(NodeAttribute::class.java)
        return (na?.value("required")?.asBoolean() ?: false)
                || isKotlinRequired(field)
    }

    private fun isKotlinRequired(field: FieldInfo): Boolean {
        // probably the only way to know is to introspect the KClass from which the method
        // is derived, because Jandex returnType won't carry the nullability
        try {
            // first check for Kotlin metadata - if missing it is a Java class and we can't know
            val kotlinMetadata = field.declaringClass().annotation("kotlin.Metadata")
            if (kotlinMetadata != null) {
                val kClass = classFromName(field.declaringClass().name(), true).kotlin
                // property with same name in same declaring class and no arguments must return right type
                val kProp = kClass.declaredMemberProperties
                    .find { p -> p.name == field.name() }
                val considerNullable = kProp?.returnType?.isMarkedNullable ?: true
                return !considerNullable
            }
        } catch (cnfe: ClassNotFoundException) {
            logger.warn("Unable to find class for ${field.declaringClass().name()}")
        }
        return false
    }

    private fun isKotlinRequired(getter: MethodInfo): Boolean {
        // probably the only way to know is to introspect the KClass from which the method
        // is derived, because Jandex returnType won't carry the nullability
        try {
            // first check for Kotlin metadata - if missing it is a Java class and we can't know
            val kotlinMetadata = getter.declaringClass().annotation("kotlin.Metadata")
            if (kotlinMetadata != null) {
                val kClass = classFromName(getter.declaringClass().name(), true).kotlin
                // function with same name in same declaring class and no arguments must return right type
                val kFun = kClass.declaredFunctions
                    .find { f -> f.name == getter.name() && f.parameters.isEmpty() }
                val considerNullable = kFun?.returnType?.isMarkedNullable ?: true
                return !considerNullable
            }
        } catch (cnfe: ClassNotFoundException) {
            logger.warn("Unable to find class for ${getter.declaringClass().name()}")
        }
        return false
    }

    private fun isCollectionType(clsType: ParameterizedType) : Boolean {
        // this will be ugly because the type info is a bit flaky
        // in particular, this likely isn't an indexed class in the nominal case
        val testCls = classFromName(clsType.name(), false)
        return java.util.Collection::class.java.isAssignableFrom(testCls)
                || Collection::class.java.isAssignableFrom(testCls)
    }

    private fun extractEnumType(type: Type) : Type? {
        val baseType = getElementType(type)
        val cls = classFromName(baseType.name(), true)
        return if (cls.isEnum) baseType else null
    }

    /**
     * If type is an array or collection type, return the element type,
     * otherwise just type. Generally to identify the "base" type in case
     * it is a collection. Obviously this will break for collection of collection, etc.
     */
    private fun getElementType(type: Type): Type {
        return when (type.kind()) {
            Type.Kind.ARRAY -> getElementType(type.asArrayType())
            Type.Kind.PARAMETERIZED_TYPE -> if (isCollectionType(type.asParameterizedType())) getElementType((type.asParameterizedType())) else type
            else -> type
        }
    }

    private fun getElementType(arrayType: ArrayType): Type {
        return arrayType.component()
    }

    private fun getElementType(collectionType: ParameterizedType): Type {
        // this should have been tested to be assignable from Colletion (java or kotlin)
        // so it should always have one parameter which is the element type
        // TODO check for wildcard type or upper bounds?
        if (collectionType.arguments().size != 1) {
            throw ModelException("Unable to handle parameterized type ${collectionType}")
        }
        return collectionType.arguments()[0]
    }

    /**
     * get the model scalar type for the specified Type object. Assumes that scalars are
     * initialized in the model first.
     * @return Scalar reference, or null if not scalar or not found
     */
    private fun getSchemaScalar(fieldOrMethodType: Type): GraphQLScalar? {
        // this should return true if the type is a primitive, mapped as scalar,
        // or if the type is an array or collection of the above
        return when (fieldOrMethodType.kind()) {
            Type.Kind.PRIMITIVE -> schemaModel.getScalarType(fieldOrMethodType.name())
            Type.Kind.PARAMETERIZED_TYPE ->
                if (isCollectionType(fieldOrMethodType.asParameterizedType())) schemaModel.getScalarType(
                    getElementType(fieldOrMethodType.asParameterizedType()).name()
                )
                else null

            Type.Kind.CLASS -> schemaModel.getScalarType(fieldOrMethodType.name())
            Type.Kind.ARRAY -> getSchemaScalar(getElementType(fieldOrMethodType.asArrayType()))
            else -> null
        }
    }

    @Throws(ClassNotFoundException::class)
    private fun classFromName(inName: DotName, hintIndexed: Boolean): Class<*> {
        // basically this might need to search the project class path or the loader class path
        // as a lot of return types will not be there
        val firstLoader = if (hintIndexed) projectClassLoader else this::class.java.classLoader
        val secondLoader = if (!hintIndexed) this::class.java.classLoader else projectClassLoader
        val fqName = inName.toString()

        return try {
            firstLoader.loadClass(fqName)
        } catch (notFound: ClassNotFoundException) {
            secondLoader.loadClass(fqName)
        }
    }

    private fun attrNameFromField(field: FieldInfo): String {
        val attrAnnotation = field.annotation(NodeAttribute::class.java)
        val jsonAnnotation = field.annotation(JsonProperty::class.java)
        val attrName = attrAnnotation?.value("name")?.asString() ?: jsonAnnotation?.value("value")?.asString()
        return attrName ?: field.name()
    }

    private fun attrNameFromMethod(mtd: MethodInfo): String {
        val attrAnnotation = mtd.annotation(NodeAttribute::class.java)
        val jsonAnnotation = mtd.annotation(JsonProperty::class.java)
        val attrName = attrAnnotation?.value("name")?.asString() ?: jsonAnnotation?.value("value")?.asString()
        return attrName ?: attrNameFromGetter(mtd.name())
    }

    private fun attrNameFromGetter(mtdName: String): String {
        if (mtdName.startsWith("get"))
            return decapitalize(mtdName.substring(3))
        if (mtdName.startsWith("is"))
            return decapitalize(mtdName.substring(2))
        return decapitalize(mtdName)
    }

    /**
     * Check if the element type is an indexed class annotated
     * @NodeEntity(type=RELATION, label="rel_name")
     * and return "rel_name" or else null.
     */
    private fun relationNameFromElement(elementType: Type) : String? {
        val elemCls = index.getClassByName(elementType.name())
        val ne = elemCls?.annotation(NodeEntity::class.java)
        if ("RELATION" == ne?.value("type")?.asEnum()) {
            val lbl = ne.value("label").asStringArray()
            if (lbl.size == 1)
                return lbl[0]
        }
        return null
    }

    private fun keyAnnotation(cls: ClassInfo, keyAnnoType: Class<out Annotation>) : AnnotationInstance? {
        var annotated = cls.annotation(keyAnnoType)
        if (annotated == null) {
            val superCls = index.getClassByName(cls.superName())
            if (superCls != null)
                annotated = keyAnnotation(superCls, keyAnnoType)
        }
        return annotated
    }

    private fun determineScanLocation(cls: ClassInfo, keyAnnoType: Class<out Annotation>) : ScanLocation {
        // look for @NodeKey annotation on this class
        val keyAnnotation = keyAnnotation(cls, keyAnnoType)
        if (keyAnnotation == null)
            throw ClassModelException(cls.name().toString(), "Unable to find @${keyAnnoType.simpleName} for class")

        // for non-entity base classes there may not be @NodeKey, default to fields over methods
        return if (keyAnnotation.target()?.kind() == AnnotationTarget.Kind.METHOD)
            ScanLocation.METHODS
        else
            ScanLocation.FIELDS
    }

    private fun decapitalize(name: String): String {
        return name.substring(0 until 1).lowercase(Locale.getDefault()) + name.substring(1)
    }

    companion object {
        val logger = LoggerFactory.getLogger(SchemaGeneration::class.java)
    }
}