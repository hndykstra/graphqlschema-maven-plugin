package com.opsysinc.graphql.plugin.model

import com.opsysinc.graphql.plAugin.ModelException
import com.opsysinc.graphql.plugin.ScalarMapping
import org.jboss.jandex.ClassInfo
import org.jboss.jandex.DotName
import org.jboss.jandex.Type
import java.io.BufferedWriter
import java.nio.file.Path
import java.time.*
import kotlin.io.path.writer

/**
 * Represents a class that appeared in scanning another class. Tracking these helps ensure
 * that all relationships are resolved so the schema is complete.
 */
class ModelClassMention (val mentionedClass: DotName, val sourceClass: ClassInfo, val attributeName : String?)

/**
 * Represents the schema related information for an attribute that is a GraphQL scalar type or
 * collection of scalar types.
 */
class SchemaModel (includeNeo4jScalars: Boolean = false) {
    private val enumTypes = mutableMapOf<DotName, EnumType>()

    /** Maps fully qualified class name to the GraphQLScalar type */
    private val scalarTypes : MutableMap<String, GraphQLScalar>
    private val declaredMappings = mutableSetOf<GraphQLScalar>()

    private val modelTypes = mutableMapOf<DotName, SchemaTypeModel>()
    private val interfaces = mutableMapOf<DotName, InterfaceTypeModel>()
    private val mentions = mutableListOf<ModelClassMention>()
    private val ignoreMentions = mutableListOf<ModelClassMention>()

    init {
        // add the basic scalar mappings for defined scalar types
        scalarTypes = mutableMapOf(
            java.lang.Integer::class.java.name to GraphQLScalar.GraphQLInt,
            java.lang.Long::class.java.name to GraphQLScalar.GraphQLInt,
            java.lang.Short::class.java.name to GraphQLScalar.GraphQLInt,
            Int::class.java.name to GraphQLScalar.GraphQLInt,
            Long::class.java.name to GraphQLScalar.GraphQLInt,
            Short::class.java.name to GraphQLScalar.GraphQLInt,
            java.lang.Integer.TYPE.name to GraphQLScalar.GraphQLInt,
            java.lang.Long.TYPE.name to GraphQLScalar.GraphQLInt,
            java.lang.Short.TYPE.name to GraphQLScalar.GraphQLInt,

            java.lang.Float::class.java.name to GraphQLScalar.GraphQLFloat,
            java.lang.Double::class.java.name to GraphQLScalar.GraphQLFloat,
            java.lang.Number::class.java.name to GraphQLScalar.GraphQLFloat,
            Float::class.java.name to GraphQLScalar.GraphQLFloat,
            Double::class.java.name to GraphQLScalar.GraphQLFloat,
            java.lang.Float.TYPE.name to GraphQLScalar.GraphQLFloat,
            java.lang.Double.TYPE.name to GraphQLScalar.GraphQLFloat,

            java.lang.Boolean::class.java.name to GraphQLScalar.GraphQLBoolean,
            Boolean::class.java.name to GraphQLScalar.GraphQLBoolean,
            java.lang.Boolean.TYPE.name to GraphQLScalar.GraphQLBoolean,

            java.lang.String::class.java.name to GraphQLScalar.GraphQLString,
            String::class.java.name to GraphQLScalar.GraphQLString
        )

        if (includeNeo4jScalars) {
            NEO4J_SCALARS.values.forEach { declaredMappings.add(it) }
            scalarTypes.putAll(NEO4J_SCALARS)
        }
    }

    fun addScalarMapping(mapping: ScalarMapping) {
        val asScalar = GraphQLScalar(mapping.scalarName)
        declaredMappings.add(asScalar)
        mapping.classes.forEach { clsName ->
            scalarTypes[clsName] = asScalar
        }
    }

    fun addInterface(interfaceData: InterfaceTypeModel) {
        interfaces[interfaceData.classInfo.name()] = interfaceData
        modelTypes[interfaceData.classInfo.name()] = interfaceData
    }

    fun addType(typeData: SchemaTypeModel) {
        modelTypes[typeData.classInfo.name()] = typeData
    }

    fun hasTypeModel(typeName: DotName) : Boolean {
        return modelTypes.containsKey(typeName) || interfaces.containsKey(typeName)
    }

    fun ignoreMention(mentionedType: Type, sourceClass: ClassInfo, attributeName: String?) {
        ignoreMention(mentionedType.name(), sourceClass, attributeName)
    }

    fun ignoreMention(mentionedClass: DotName, sourceClass: ClassInfo, attributeName: String?) {
        ignoreMentions.add(ModelClassMention(mentionedClass, sourceClass, attributeName))
    }

    fun addMention(mentionedType: Type, sourceClass: ClassInfo, attributeName: String?) {
        addMention(mentionedType.name(), sourceClass, attributeName)
    }

    fun addMention(mentionedClass: DotName, sourceClass: ClassInfo, attributeName: String?) {
        mentions.add(ModelClassMention(mentionedClass, sourceClass, attributeName))
    }

    /**
     * Ensure all model elements are fully resolved.
     * Resolve could throw errors but we should probably collect them and return them
     * so issues can be logged and solved in one batch
     */
    fun resolve() : List<ModelException> {
        val exceptions = mutableListOf<ModelException>()
        val modelNames = modelTypes.keys.toSet()
        modelNames.forEach { name ->
            val modelType = modelTypes[name]!!
            try {
                modelType.resolve(this)
            } catch (me : ModelException) {
                exceptions.add(me)
                // this type is just too broken to generate
                modelTypes.remove(name)
            }
        }
        return exceptions
    }

    fun validate() : List<ModelException> {
        // basically, everything will be handled by resolve and mentions
        val errors = mutableListOf<ModelException>()
        errors.addAll(resolve())

        // this may never result in anything, because resolve() will catch most all
        // but in case something ran across a reference to a class that is not an entity
        // and resolve did not catch it, we can put it here
        mentions.forEach { mention ->
            val ignoreIt = ignoreMentions.any {
                it.mentionedClass == mention.mentionedClass
                        && it.sourceClass.name().equals(mention.sourceClass.name())
                        && it.attributeName == mention.attributeName
            }
            if (!ignoreIt) {
                // verify that the mentioned class is either a valid scalar type or entity type
                if (!scalarTypes.containsKey(mention.mentionedClass.toString())
                    && !modelTypes.containsKey(mention.mentionedClass)) {
                    errors.add(ModelException("Unrecognized type '${mention.mentionedClass}' was encountered in type '${mention.sourceClass.name()}' at '${mention.attributeName}"))
                }
            }
        }
        return errors
    }

    fun generateFragments(toDir: Path) : Collection<Path> {
        val concreteFragments = modelTypes.values.asSequence()
            .map { type ->
                val fName = type.fragmentName()
                val file = toDir.resolve("${fName}.fragment")
                BufferedWriter(file.writer()).use { w ->
                    w.write(type.generateFragment())
                    w.flush()
                }
                file
            }
            .toMutableList()
        return concreteFragments
    }

    fun generateSchema(toFile: Path) {
        BufferedWriter(toFile.writer()).use { w ->
            // first scalars but not enums (enums generated separately)
            declaredMappings.asSequence()
                .filter { !enumTypes.values.contains(it) }
                .forEach {
                w.write("scalar ${it.scalarRepresentation}")
                w.newLine()
            }
            w.newLine()
            w.flush()

            // then enums
            enumTypes.values.sortedBy { it.scalarRepresentation }
                .forEach {
                    w.write(it.generate())
                    w.newLine()
                    w.newLine()
                }
            w.flush()

            // then interfaces
            interfaces.values.sortedBy { it.schemaName }
                .forEach {
                    w.write(it.generate())
                    w.newLine()
                    w.newLine()
                }
            w.flush()

            // then non-interface types
            modelTypes.values.asSequence()
                .filter { it !is InterfaceTypeModel }
                .sortedBy { it.schemaName }
                .forEach {
                    w.write(it.generate())
                    w.newLine()
                    w.newLine()
                }
            w.flush()
        }
    }

    fun getTypeModel(clsType: Type) : SchemaTypeModel? {
        return modelTypes[clsType.name()]
    }

    fun findFromTypeModel(referencing: SchemaRelationTypeModel) : SchemaTypeModel? {
        // find a model type that has an attribute referencing the model
        // used in case the @StartNode is not supplied, to resolve relation entities.
        return modelTypes.values.asSequence()
            .filter { type ->
                type.relationshipAttributes
                    .any { attr -> /* attribute target type matches referencing type name */
                        attr.schemaType.name().equals(referencing.classInfo.name())
                    }
            }
            .firstOrNull()
    }

    fun getEnum(cls: DotName) : EnumType? {
        return enumTypes[cls]
    }

    fun hasEnum(cls: DotName) : Boolean {
        return getEnum(cls) != null
    }

    fun addEnumType(cls: DotName, enumInfo: EnumType) {
        enumTypes[cls] = enumInfo
        scalarTypes[cls.toString()] = enumInfo
        declaredMappings.add(enumInfo)
    }

    fun hasScalar(fqClsName: String) : Boolean {
        return scalarTypes[fqClsName] != null
    }

    fun getScalarType(fqClsName: String) : GraphQLScalar? {
        return scalarTypes[fqClsName]
    }

    fun getScalarType(typeName: DotName) : GraphQLScalar? {
        return getScalarType(typeName.toString())
    }

    fun getInterface(ifName: DotName) : InterfaceTypeModel? {
        return interfaces[ifName]
    }

    companion object {
        /** Neo4j supports "Point" type with its own class. */
        val NEO4J_SCALAR_POINT = GraphQLScalar("Point")
        val NEO4J_SCALAR_DATE = GraphQLScalar("Date")
        val NEO4J_SCALAR_TIME = GraphQLScalar("Time")
        val NEO4J_SCALAR_LOCALTIME = GraphQLScalar("LocalTime")
        val NEO4J_SCALAR_DATETIME = GraphQLScalar("DateTime")
        val NEO4J_SCALAR_LOCALDATETIME = GraphQLScalar("LocalDateTime")
        val NEO4J_SCALAR_DURATION = GraphQLScalar("Duration")

        /** Mostly these are the native Neo4j bindings, except we support Instant as a type */
        val NEO4J_SCALARS = mapOf<String, GraphQLScalar>(
            "org.neo4j.graphdb.spatial.Point" to NEO4J_SCALAR_POINT,
            LocalDate::class.java.name to NEO4J_SCALAR_DATE,
            LocalTime::class.java.name to NEO4J_SCALAR_LOCALTIME,
            OffsetTime::class.java.name to NEO4J_SCALAR_TIME,
            ZonedDateTime::class.java.name to NEO4J_SCALAR_DATETIME,
            LocalDateTime::class.java.name to NEO4J_SCALAR_LOCALDATETIME,
            Instant::class.java.name to NEO4J_SCALAR_DATETIME,
            Duration::class.java.name to NEO4J_SCALAR_DURATION
        )

        val ABSTRACT_INTERFACE_SUFFIX = "Intf"
    }
}