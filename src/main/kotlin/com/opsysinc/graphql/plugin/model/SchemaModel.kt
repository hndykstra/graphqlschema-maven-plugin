package com.opsysinc.graphql.plugin.model

import com.opsysinc.graphql.plAugin.ModelException
import com.opsysinc.graphql.plugin.ScalarMapping
import com.opsysinc.graphql.plugin.Util
import org.apache.maven.plugin.logging.Log
import org.jboss.jandex.ClassInfo
import org.jboss.jandex.DotName
import org.jboss.jandex.Type
import java.io.BufferedWriter
import java.io.PrintWriter
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
class SchemaModel (val log: Log, includeNeo4jScalars: Boolean = false) {
    private val enumTypes = mutableMapOf<DotName, EnumType>()

    /** Maps fully qualified class name to the GraphQLScalar type */
    private val scalarTypes : MutableMap<String, GraphQLScalar>
    private val declaredMappings = mutableSetOf<GraphQLScalar>()

    private val modelTypes = mutableMapOf<DotName, SchemaTypeModel>()
    private val interfaces = mutableMapOf<DotName, InterfaceTypeModel>()
    private val mentions = mutableListOf<ModelClassMention>()
    private val ignoreMentions = mutableListOf<ModelClassMention>()

    val typeModels : Sequence<SchemaTypeModel>
        get() = modelTypes.values.asSequence()

    init {
        // add the basic scalar mappings for defined scalar types
        scalarTypes = mutableMapOf(
            Integer::class.java.name to GraphQLScalar.GraphQLInt,
            java.lang.Long::class.java.name to GraphQLScalar.GraphQLInt,
            java.lang.Short::class.java.name to GraphQLScalar.GraphQLInt,
            Int::class.java.name to GraphQLScalar.GraphQLInt,
            Long::class.java.name to GraphQLScalar.GraphQLInt,
            Short::class.java.name to GraphQLScalar.GraphQLInt,
            Integer.TYPE.name to GraphQLScalar.GraphQLInt,
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
        log.info("Add scalar ${asScalar.scalarRepresentation}")
        declaredMappings.add(asScalar)
        mapping.classes.forEach { clsName ->
            scalarTypes[clsName] = asScalar
        }
    }

    fun addInterface(interfaceData: InterfaceTypeModel) {
        log.info("Add interface ${interfaceData.schemaName}")
        interfaces[interfaceData.classInfo.name()] = interfaceData
        modelTypes[interfaceData.classInfo.name()] = interfaceData
    }

    fun addType(typeData: SchemaTypeModel) {
        log.info("Add type ${typeData.schemaName}")
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

    fun generateConstraints(toFile: Path) {
        BufferedWriter(toFile.writer()).use { w ->
            modelTypes.values.asSequence()
                .forEach { schemaType ->
                    if (schemaType !is SchemaRelationTypeModel && schemaType.key.size == 1) {
                        val keyAttr = schemaType.key.first()
                        val keyIndexName = "index_${schemaType.schemaName}_${keyAttr.schemaName}"
                        val keyConstrName = "constraint_${schemaType.schemaName}_${keyAttr.schemaName}"
                        w.write("CREATE INDEX ${keyIndexName} IF NOT EXISTS FOR (n:${schemaType.schemaName}) ON (n.${keyAttr.schemaName})")
                        w.newLine()
                        w.write("CREATE CONSTRAINT ${keyConstrName} IF NOT EXISTS FOR (n:${schemaType.schemaName}) REQUIRE n.${keyAttr.schemaName} IS UNIQUE;")
                        w.newLine()
                        w.newLine()
                    }
                }
            w.flush()
        }
    }

    fun generateQueries(toDir: Path, filter: (input: SchemaTypeModel, filename: String) -> Boolean) {
        for (model in modelTypes.values.asSequence()) {
            if (model !is InterfaceTypeModel &&
                model !is SchemaRelationTypeModel &&
                filter(model, "")) {
                // for now we'll just generate each model
                // maybe we need to enable some logic to handle abstractions and interfaces
                // or classes for which queries aren't needed.
                generateGetAllQuery(model, toDir, filter)
                generateGetByKeyQuery(model, toDir, filter)
            }
        }
    }

    fun generateRepositories(toDir: Path, baseClass: String?, packageName: String, isJava: Boolean,
                             filter : (outputFileName: String) -> Boolean) : List<Path> {
        log.info("Generating repositories for ${modelTypes.size} model types in ${toDir.toAbsolutePath()}")
        val generatedFiles = mutableListOf<Path>()
        for (model in modelTypes.values.asSequence()) {
            if (model !is InterfaceTypeModel && model !is SchemaRelationTypeModel) {
                val path = generateRepository(model, toDir, baseClass, packageName, isJava, filter)
                if (path != null) {
                    generatedFiles.add(path)
                }
            }
        }
        return generatedFiles
    }

    fun generateRepository(model: SchemaTypeModel, toDir: Path,
                           baseClass: String?, packageName: String,
                           isJava: Boolean = true,
                           filter: (filename: String) -> Boolean) : Path? {
        if (model is SchemaRelationTypeModel) {
            throw IllegalArgumentException("Cannot generate a repository for a relation type")
        }
        val ext = if (isJava) ".java" else ".kt"
        val repoClassName = "${model.classInfo.simpleName()}Repository"
        val modelClassName = model.classInfo.simpleName()
        val filename = "$repoClassName$ext"
        val file = toDir.resolve(filename)
        log.info("Generating ${file.toAbsolutePath()}")
        val lineEnd = if (isJava) ";" else ""
        val fqType = model.classInfo.name().toString()
        val baseClassPackage = if (baseClass.isNullOrEmpty())
            ""
        else if (baseClass.lastIndexOf('.') == -1)
            packageName
        else
            baseClass.substring(0, baseClass.lastIndexOf('.'))
        val baseClassExtends = if (baseClass.isNullOrEmpty())
            "AbstractNodeRepository<$modelClassName>"
        else if (baseClass.lastIndexOf('.') == -1)
            "$baseClass<$modelClassName>"
        else
            "${baseClass.substring(baseClass.lastIndexOf('.') + 1)}<$modelClassName>"

        if (filter(filename)) {
            PrintWriter(BufferedWriter(file.writer())).use { w ->
                w.println("package ${packageName}$lineEnd")
                w.println()
                w.println("import $fqType$lineEnd")
                if (!baseClass.isNullOrEmpty() && baseClassPackage != packageName) {
                    w.println("import $baseClass$lineEnd")
                }
                w.println()
                w.println("import com.artisanecm.graphql.provider.GraphQLProvider$lineEnd")
                w.println("import com.artisanecm.graphql.repository.KeyGeneratorFactory$lineEnd")
                w.println("import com.artisanecm.graphql.repository.nodeentity.NodeConverterFactory$lineEnd")
                if (baseClass.isNullOrEmpty()) {
                    w.println("import com.artisanecm.graphql.repository.AbsractNodeRepository$lineEnd")
                }
                w.println("import com.artisanecm.neo4j.util.JsonSupport$lineEnd")
                w.println()
                w.println("import jakarta.enterprise.context.ApplicationScoped$lineEnd")
                w.println("import jakarta.inject.Inject$lineEnd")
                if (isJava) {
                    w.println("import java.util.List$lineEnd")
                    w.println("import java.util.Map$lineEnd")
                }
                w.println()
                w.println("/**")
                w.println(" * Basic generated repository for ${modelClassName}.")
                w.println(" * Changes made here may not be retained if the class is regenerated.")
                w.println(" */")
                w.println("@ApplicationScoped")
                if (isJava) {
                    val keyTypes =
                        model.key.joinToString(separator = ", ") { "final ${Util.javaScalarRepresentation(it.schemaType.scalarRepresentation)} ${it.schemaName}" }
                    val keyMap = model.key.joinToString(separator = ", ") { "\"${it.schemaName}\", ${it.schemaName}" }
                    w.println("public class $repoClassName extends $baseClassExtends {")
                    w.println("  JsonSupport json;")
                    w.println()
                    w.println("  @Inject")
                    w.println("  public $repoClassName(final GraphQLProvider graphQLProvider, final NodeConverterFactory nodeConverterFactory, final KeyGeneratorFactory keyGeneratorFactory, final JsonSupport json) {")
                    w.println("    super(graphQLProvider, $modelClassName.class, nodeConverterFactory, keyGeneratorFactory);")
                    w.println("    this.json = json;")
                    w.println("  }")
                    w.println()
                    w.println("  /**")
                    w.println("   * Find all instances of ${modelClassName}.")
                    w.println("   * @return a list of all ${modelClassName} instances.")
                    w.println("   */")
                    w.println("  public List<$modelClassName> getAll${Util.pluralize(modelClassName)}() {")
                    w.println(
                        "    return json.toObjectList(entityListQuery(\"${
                            Util.decapitalize(
                                Util.pluralize(
                                    modelClassName
                                )
                            )
                        }\", Map.of()), $modelClassName.class);"
                    )
                    w.println("  }")
                    w.println()
                    w.println("  /**")
                    w.println("   * Find a single instance of ${modelClassName} by its primary key.")
                    model.key.forEach { keyAttr ->
                        w.println("   * @param ${keyAttr.schemaName} Primary key value")
                    }
                    w.println("   * @return the matching ${modelClassName} instance, or null if not found.")
                    w.println("   */")
                    w.println("  public $modelClassName get${modelClassName}ByKey($keyTypes) {")
                    w.println("    final Map<String, Object> params = Map.of($keyMap);")
                    w.println(
                        "    final Map<String, Object> row = singleEntityQuery(\"${
                            Util.decapitalize(
                                modelClassName
                            )
                        }ByKey\", params);"
                    )
                    w.println("    if (row == null) {")
                    w.println("      return null;")
                    w.println("    }")
                    w.println("    return json.toObject(row, $modelClassName.class);")
                    w.println("  }")
                    w.println("}")
                } else {
                    // kotlin
                    val keyTypes = model.key.joinToString(separator = ", ") {
                        "${it.schemaName}: ${
                            Util.kotlinScalarRepresentation(it.schemaType.scalarRepresentation)
                        }"
                    }
                    val keyMap = model.key.joinToString(separator = ", ") { "\"${it.schemaName}\" to ${it.schemaName}" }
                    w.println("class $repoClassName @Inject constructor(graphQLProvider: GraphQLProvider, nodeConverters: NodeConverterFactory, keyGenerators: KeyGeneratorFactory, val json: JsonSupport)")
                    w.println("    : $baseClassExtends(graphQLProvider, $modelTypes::class.java, nodeConverters, keyGenerators) {")
                    w.println()
                    w.println()
                    w.println("  /**")
                    w.println("   * Find all instances of ${modelClassName}.")
                    w.println("   * @return a list of all ${modelClassName} instances.")
                    w.println("   */")
                    w.println("  fun getAll${Util.pluralize(modelClassName)}() : List<$modelClassName> {")
                    w.println(
                        "    return json.toObjectList(entityListQuery(\"${
                            Util.decapitalize(
                                Util.pluralize(
                                    modelClassName
                                )
                            )
                        }\", mapOf()), $modelClassName::class.java)"
                    )
                    w.println("  }")
                    w.println()
                    w.println("  /**")
                    w.println("   * Find a single instance of ${modelClassName} by its primary key.")
                    model.key.forEach { keyAttr ->
                        w.println("   * @param ${keyAttr.schemaName} Primary key value")
                    }
                    w.println("   * @return the matching ${modelClassName} instance, or null if not found.")
                    w.println("   */")
                    w.println("  fun get${modelClassName}ByKey($keyTypes) : $modelClassName? {")
                    w.println("    val params = mapOf($keyMap)")
                    w.println("    val row = singleEntityQuery(\"${Util.decapitalize(modelClassName)}ByKey\", params)")
                    w.println("    if (row == null) {")
                    w.println("      return null")
                    w.println("    }")
                    w.println("    return json.toObject(row, $modelClassName::class.java)")
                    w.println("  }")
                    w.println("}")
                }
            }
            return file
        } else {
            log.info("Skipping $filename because an existing source file was found")
            return null
        }
    }

    private fun generateGetAllQuery(model: SchemaTypeModel, toDir: Path,
                                    filter: (model: SchemaTypeModel, filename: String) -> Boolean) {
        val schemaQueryName = "${Util.decapitalize(model.schemaName)}"
        val name = Util.pluralize(schemaQueryName)
        val targetFileName = "${name}.query"
        if (filter(model, targetFileName)) {
            val fragmentName = "${model.schemaName}Fields"
            val fragments = mutableSetOf(fragmentName)
            val rels = mutableMapOf<String, RecursiveRelationData>()
            // default behavior of relationships that are included in default queries.
            model.relationshipAttributes.forEach { r ->
                val rName = r.schemaName
                val relatedType = getTypeOrInterfaceModel(r.schemaType)
                if (relatedType != null) {
                    if (relatedType is SchemaRelationTypeModel) {
                        rels[rName] = buildRelationshipRels(r, relatedType, mutableSetOf())
                    } else {
                        rels[rName] = buildDeepRels(r, relatedType, mutableSetOf())
                    }
                }
            }

            // accumulate the fragments (recursively) from all the relations that will use them
            rels.values.forEach { it.buildFragments(fragments) }

            BufferedWriter(toDir.resolve(targetFileName).writer()).use { w ->
                w.write("params: {}")
                w.newLine()
                w.write("fragments:")
                w.newLine()
                writeFragments(w, fragments)
                w.write("query: |")
                w.newLine()
                w.write(" query $name {")
                w.newLine()
                w.write("    $schemaQueryName {")
                w.newLine()
                w.write("      ...$fragmentName")
                w.newLine()
                rels.forEach { (name, data) ->
                    writeRelation(w, name, data, 3)
                }
                w.write("    }")
                w.newLine()
                w.write("  }")
                w.newLine()
            }
        } else {
            log.info("Excluding $targetFileName because an existing source file was found")
        }
    }

    fun generateGetByKeyQuery(model: SchemaTypeModel, toDir: Path,
                              filter: (model: SchemaTypeModel, filename: String) -> Boolean) {
        val name = "${Util.decapitalize(model.schemaName)}ByKey"
        val targetFileName = "${name}.query"
        if (filter(model, targetFileName)) {
            val schemaQueryName = "${Util.decapitalize(model.schemaName)}"
            val fragmentName = "${model.schemaName}Fields"
            val fragments = mutableSetOf(fragmentName)
            val rels = mutableMapOf<String, RecursiveRelationData>()
            // default behavior of relationships that are included in default queries.
            model.relationshipAttributes.forEach { r ->
                val rName = r.schemaName
                val relatedType = getTypeOrInterfaceModel(r.schemaType)
                if (relatedType != null) {
                    rels[rName] = buildDeepRels(r, relatedType, mutableSetOf())
                }
            }

            // accumulate the fragments (recursively) from all the relations that will use them
            rels.values.forEach { it.buildFragments(fragments) }

            BufferedWriter(toDir.resolve(targetFileName).writer()).use { w ->
                w.write("params:")
                w.newLine()
                model.key.forEach { keyAttr ->
                    w.write("  ${keyAttr.schemaName}: ${keyAttr.schemaType.scalarRepresentation}")
                    w.newLine()
                }
                w.write("fragments:")
                w.newLine()
                writeFragments(w, fragments)
                w.write("query: |")
                w.newLine()
                val params = model.key.asSequence()
                    .map { keyAttr -> "$${keyAttr.schemaName}: ${keyAttr.schemaType.scalarRepresentation}!" }
                    .joinToString(separator = ", ")
                val paramConditions = model.key.asSequence()
                    .map { keyAttr -> "${keyAttr.schemaName}: $${keyAttr.schemaName}" }
                    .joinToString(separator = ", ")
                w.write("  query $name($params) {")
                w.newLine()
                w.write("    $schemaQueryName($paramConditions) {")
                w.newLine()
                w.write("      ...$fragmentName")
                w.newLine()
                rels.forEach { (name, data) ->
                    writeRelation(w, name, data, 3)
                }
                w.write("    }")
                w.newLine()
                w.write("  }")
                w.newLine()
            }
        } else {
            log.info("Excluding $targetFileName because an existing source file was found")
        }
    }

    private fun writeFragments(writeTo: BufferedWriter, fragments: Set<String>) {
        fragments.forEach { fragment ->
            writeTo.write("  - $fragment")
            writeTo.newLine()
        }
    }

    private fun writeRelation(writeTo: BufferedWriter, schemaName: String, relation: RecursiveRelationData, indentDepth: Int) {
        val indentStr = "  ".repeat(indentDepth)
        writeTo.write("$indentStr$schemaName {")
        writeTo.newLine()
        if (relation.isRecursive) {
            writeTo.write("$indentStr  ...${relation.relatedType.fragmentName()}")
            writeTo.newLine()
            relation.includedRelations.forEach { includedRelation ->
                writeRelation(writeTo, includedRelation.relationName, includedRelation, indentDepth + 1)
            }
        } else {
            writeTo.write("$indentStr  ...${relation.relatedType.fragmentName()}")
            writeTo.newLine()
        }
        writeTo.write("$indentStr}")
        writeTo.newLine()
    }

    private fun buildDeepRels(relation: RelationshipAttributeModel,
                              relatedType: SchemaTypeModel,
                              seen: Set<String>) : RecursiveRelationData {
        val notPreviouslySeen = !seen.contains(relatedType.schemaName)
        val isDeep = notPreviouslySeen &&
                (relation.cascades.contains("DELETE") || relation.cascades.contains("ALL"))
        val thisType = RecursiveRelationData(isDeep, relation.schemaName, relation, relatedType)
        val newSeen = seen.toMutableSet()
        newSeen.add(relatedType.schemaName)
        if (isDeep) {
            relatedType.relationshipAttributes.forEach { r ->
                val rType = getTypeOrInterfaceModel(r.schemaType)
                if (rType != null) {
                    if (rType is SchemaRelationTypeModel) {
                        thisType.add(buildRelationshipRels(r, rType, newSeen))
                    } else {
                        thisType.add(buildDeepRels(r, rType, newSeen))
                    }
                }
            }
        }
        return thisType
    }

    private fun buildRelationshipRels(relationFromSource: RelationshipAttributeModel,
                                      relatedType: SchemaRelationTypeModel,
                                      seen: Set<String>) : RecursiveRelationData {
        val fromModel = relatedType.fromModel ?: throw IllegalArgumentException("Incomplete relation model - no from Model type inferred")
        val toModel = relatedType.toModel ?: throw kotlin.IllegalArgumentException("Incomplete relation model - no to Model type inferred")

        // here: relationFromSource is a relationship from a node-type entity to a relation-type entity
        // this is always "ddep" because we must follow the relationship at least as far as the end node
        // then we'll need to follow either the @StartNode or @EndNode, depending on the directionality
        // of the relationFromSource
        val thisType = RecursiveRelationData(true, relationFromSource.schemaName, relationFromSource, relatedType)
        if (relationFromSource.direction == RelationDirection.OUT) {
            // we only want to include the toModel to avoid self-reference
            // there is no RelationshipAttributeModel for EndNode, but we'll need a synthetic one
            // - name from relatedType.toName, type from toModel, cascade from relationFromSource
            val syntheticRelation = RelationshipAttributeModel(relatedType.toName!!, relatedType.toType!!,
                relatedType.relationName, relationFromSource.direction, acrossRelationEntity = true,
                required = true, collection = false, cascades = relationFromSource.cascades
            )
            thisType.add(buildDeepRels(syntheticRelation, toModel, seen))
        } else {
            // we only want to include the frp,Model to avoid self-reference
            // there is no RelationshipAttributeModel for EndNode, but we'll need a synthetic one
            // - name from relatedType.fromName, type from fromModel, cascade from relationFromSource
            val syntheticRelation = RelationshipAttributeModel(relatedType.fromName!!, relatedType.fromType!!,
                relatedType.relationName, relationFromSource.direction, acrossRelationEntity = true,
                required = true, collection = false, cascades = relationFromSource.cascades
            )
            thisType.add(buildDeepRels(syntheticRelation, fromModel, seen))

        }
        return thisType
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

    fun getTypeOrInterfaceModel(clsType: Type) : SchemaTypeModel? {
        var mdl = getTypeModel(clsType)
        if (mdl == null)
            mdl = getInterface(clsType.name())
        return mdl
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

        const val ABSTRACT_INTERFACE_SUFFIX = "Intf"
    }
}