package com.opsysinc.graphql.plugin

import com.opsysinc.graphql.plAugin.ModelException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.ResolutionScope
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

@Mojo(name = "generate-queries",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
class GenerateQueriesMojo : AbstractModelMojo() {

    override fun execute() {
        val thisProject = project ?: throw MojoFailureException("Project is null")
        val generator = createSchemaScanner()
        val pluginErrors = mutableListOf<ModelException>()
        // run the generation

        // validate the model
        try {
            log.info("Building GraphQL schema model")
            pluginErrors.addAll(generator.scan())
            log.info("Validating model")
            val model = generator.schemaModel
            pluginErrors.addAll(model.validate())

            // Q: Should we fail the mojo if there are errors?
            // right now, no, just log a bunch of warnings.
            if (pluginErrors.isNotEmpty()) {
                log.warn("Schema model generated with errors; generated queries will be incomplete or incorrect")
                pluginErrors.forEach { log.warn(it.message) }
            } else {
                log.info("Schema model successfully built")
            }
            // export the model
            val generateResourceLocation =
                outputQueryLocation ?: "${thisProject.build.directory}/generated-resources/graphql-queries"
            val generateResourceDir = Path(generateResourceLocation)
            val packageDirStr = resourcePackage.replace('.', File.separatorChar)
            val packageDir = generateResourceDir.resolve(packageDirStr)
            Files.createDirectories(packageDir)
            val queryPath = packageDir.resolve(queryDirectory)
            Files.createDirectories(queryPath)

            val parentModels = model.typeModels
                .toMutableSet()
            model.typeModels
                .forEach {
                    it.relationshipAttributes.forEach { rel ->
                        if (rel.doesCascade("DELETE")) {
                            val type = model.getTypeModel(rel.schemaType)
                            if (type != null) {
                                parentModels.remove(type)
                                log.info("*** Excluding ${type.schemaName} because it is owned by ${it.schemaName} via ${rel.schemaName}")
                            }
                        }
                    }
                }

            model.generateQueries(queryPath) { schemaType, fileName ->
                // if an empty filename is passed, just check the schema type,
                // otherwise check for non-existence of corresponding file
                parentModels.contains(schemaType) &&
                        (fileName.isBlank() || !queryFileExists(fileName, packageDirStr))
            }

            copyResources(generateResourceLocation, false)
        } catch (mojoErr: MojoFailureException) {
            throw mojoErr
        } catch (e: Exception) {
            log.error("Failed to generate GraphQL schema output", e)
            throw MojoFailureException("Failed to generate GraphQL schema output", e)
        }
    }

    fun queryFileExists(filename: String, packagePath: String) : Boolean {
        val thisProject = project!!
        return thisProject.resources.any {
            val path = Path(it.directory).resolve(packagePath)
            return path.exists() && path.isDirectory() && path.resolve(filename).exists()
        }
    }
}