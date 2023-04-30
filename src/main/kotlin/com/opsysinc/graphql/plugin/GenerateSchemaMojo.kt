package com.opsysinc.graphql.plugin

import com.opsysinc.graphql.plAugin.ModelException
import com.opsysinc.graphql.plugin.model.SchemaModel
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Resource
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.BuildPluginManager
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.jboss.jandex.IndexReader
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.exists
import org.twdata.maven.mojoexecutor.MojoExecutor.*

@Mojo(name = "graphql-schema",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
class GenerateSchemaMojo : AbstractMojo() {

    /** Injected as the maven project object. */
    @Component
    var project : MavenProject? = null
    @Component
    var session : MavenSession? = null
    @Component
    var pluginManager : BuildPluginManager? = null

    /** From the pom file, or default to {baseDir}/target/classes/META-INF/jandex.idx */
    @Parameter(property = "indexFile")
    val indexFile : String? = null
    @Parameter(property = "outputDirectory", defaultValue = "\${project.build.directory}/generated-resources/graphql-schema")
    val outputDirectory : String? = null
    @Parameter(property = "schemaFile", defaultValue = "schema.graphql")
    val schemaFile = "schema.graphql"
    @Parameter(property = "generateFragments", defaultValue = "true")
    val generateFragments = true
    @Parameter(property = "fragmentDirectory", defaultValue = "fragments")
    val fragmentDirectory = "fragments"
    @Parameter(property="scalarMappings")
    val scalarMappings = mutableListOf<ScalarMapping>()
    @Parameter(property="includeNeo4jScalars", defaultValue = "false")
    val includeNeo4jScalars = true
    @Parameter(property = "resourcePackage", defaultValue = "")
    val resourcePackage = ""


    override fun execute() {
        // for readability, extract some project settings
        val thisProject = project!!
        val buildDir = thisProject.build.outputDirectory
        val workingProject = project ?: throw IllegalArgumentException("no project")
        val workingIndexFileLocation = indexFile ?: "$buildDir/META-INF/jandex.idx"
        val indexFilePath = Path(workingIndexFileLocation)
        if (!Files.isRegularFile(indexFilePath)) {
            throw IllegalArgumentException("index file $workingIndexFileLocation not found")
        }

        // ultimately we could scan dependencies for META-INF/jandex.idx
        // but that might be really slow
        // better maybe to add some configuration
        log.info("Loading index $indexFilePath")
        val jandex = Files.newInputStream(indexFilePath).use {IndexReader(it).read() }

        val model = SchemaModel(includeNeo4jScalars)
        scalarMappings.forEach { model.addScalarMapping(it) }
        val generator = SchemaGeneration(jandex, model, setupProjectClassloader(workingProject))
        val pluginErrors = mutableListOf<ModelException>()
        // run the generation

        // validate the model
        try {
            log.info("Building GraphQL schema model")
            pluginErrors.addAll(generator.scan())
            log.info("Validating model")
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

    private fun setupProjectClassloader(p: MavenProject) : ClassLoader {
        val classpathUrls = mutableListOf<URL>()
        p.compileClasspathElements.forEach { element ->
            classpathUrls.add(Path(element as String).toUri().toURL())
        }
        p.runtimeClasspathElements.forEach { element ->
            classpathUrls.add(Path(element as String).toUri().toURL())
        }

        return URLClassLoader(classpathUrls.toTypedArray())
    }

    private fun copyResources(fromPath: String) {
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