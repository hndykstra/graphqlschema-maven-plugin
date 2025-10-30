package com.opsysinc.graphql.plugin

import com.opsysinc.graphql.plAugin.ModelException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.ResolutionScope
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path

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
                log.warn("Schema generated with errors; generated schema will be incomplete")
                pluginErrors.forEach { log.warn(it.message) }
            } else {
                log.info("Schema model successfully built")
            }
            // export the model
            val generateResourceLocation =
                outputDirectory ?: "${thisProject.build.directory}/generated-resources/graphql-schema"
            val generateResourceDir = Path(generateResourceLocation)
            val packageDir = generateResourceDir.resolve(resourcePackage.replace(".", File.separator))
            Files.createDirectories(packageDir)
            val queryPath = packageDir.resolve(queryDirectory)
            Files.createDirectories(queryPath)

            model.generateQueries(queryPath)
        } catch (mojoErr: MojoFailureException) {
            throw mojoErr
        } catch (e: Exception) {
            log.error("Failed to generate GraphQL schema output", e)
            throw MojoFailureException("Failed to generate GraphQL schema output", e)
        }
    }
}