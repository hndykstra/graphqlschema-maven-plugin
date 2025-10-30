package com.opsysinc.graphql.plugin

import com.opsysinc.graphql.plAugin.ModelException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.ResolutionScope
import org.twdata.maven.mojoexecutor.MojoExecutor.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path


@Mojo(name = "graphql-schema",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
class GenerateSchemaMojo : AbstractModelMojo() {


    override fun execute() {
        val thisProject = this.project ?: throw MojoFailureException("Project is not set")
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
            val generateResourceLocation = outputDirectory ?: "${thisProject.build.directory}/generated-resources/graphql-schema"
            val generateResourceDir = Path(generateResourceLocation)
            val packageDir = generateResourceDir.resolve(resourcePackage.replace(".", File.separator))
            Files.createDirectories(packageDir)
            val outFile = packageDir.resolve(schemaFile)
            log.info("Writing schema to $outFile")
            model.generateSchema(outFile)

            val fragments = if (generateFragments) {
                log.info("Generating fragments")
                val fragmentPath = packageDir.resolve(fragmentDirectory)
                Files.createDirectories(fragmentPath)
                model.generateFragments(fragmentPath)
            } else {
                emptyList()
            }

            if (pluginErrors.isEmpty()) {
                // because this must execute in process-classes, we use
                // the copy-resources mojo to avoid having to explicitly
                // call it out in the pom file.
                copyResources(generateResourceLocation)
            } else {
                throw MojoFailureException("Schema generation failed due to reported errors")
            }

            val constrFile = schemaFile.replace(".graphql", ".constraint")
            log.info("Writing key constraints to $constrFile")
            model.generateConstraints(packageDir.resolve(constrFile))
        } catch (mojoErr: MojoFailureException) {
            throw mojoErr
        } catch (e: Exception) {
            log.error("Failed to generate GraphQL schema output", e)
            throw MojoFailureException("Failed to generate GraphQL schema output", e)
        }
    }

    private fun copyToTarget(generateDir: Path, copyFile: Path, classesDir: Path) {
        val relative = generateDir.relativize(copyFile)
        val copyTarget = classesDir.resolve(relative)
        Files.createDirectories(copyTarget.parent)
        Files.copy(copyFile, copyTarget, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun copyResources(fromPath: String) {
        log.info("Copy generated resources to ${project!!.build.outputDirectory}")
        executeMojo(
            plugin(
                groupId("org.apache.maven.plugins"),
                artifactId("maven-resources-plugin"),
                version("3.3.1")
            ),
            goal("copy-resources"),
            configuration(
                element("outputDirectory", project!!.build.outputDirectory),
                element("resources",
                    element("resource",
                        element("directory", fromPath))
                )
            ),
            executionEnvironment(project, session, pluginManager)
        )
    }
}