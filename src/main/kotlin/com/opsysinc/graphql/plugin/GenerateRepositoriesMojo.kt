package com.opsysinc.graphql.plugin

import com.opsysinc.graphql.plAugin.ModelException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.ResolutionScope
import org.twdata.maven.mojoexecutor.MojoExecutor.artifactId
import org.twdata.maven.mojoexecutor.MojoExecutor.configuration
import org.twdata.maven.mojoexecutor.MojoExecutor.element
import org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo
import org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment
import org.twdata.maven.mojoexecutor.MojoExecutor.goal
import org.twdata.maven.mojoexecutor.MojoExecutor.groupId
import org.twdata.maven.mojoexecutor.MojoExecutor.plugin
import org.twdata.maven.mojoexecutor.MojoExecutor.version
import java.io.File
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

@Mojo(name = "generate-repos",
    defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
class GenerateRepositoriesMojo : AbstractModelMojo() {

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
                log.warn("Schema model generated with errors; generated repositories will be incomplete or incorrect")
                pluginErrors.forEach { log.warn(it.message) }
            } else {
                log.info("Schema model successfully built")
            }
            // export the model
            val generateSourceLocation =
                sourceOutputLocation ?: "${thisProject.build.directory}/generated-sources/graphql-repos"
            val generateSourceDir = Path(generateSourceLocation)
            val repoPath = generateSourceDir.resolve(repositoryDirectory)
            Files.createDirectories(repoPath)

            val repoPackage = if (repositoryPackage.isBlank())
                "${resourcePackage}.repository"
            else if (repositoryPackage.indexOf('.') == -1)
                "${resourcePackage}.$repositoryPackage"
            else
                repositoryPackage
            log.info("Generating repositories to $repoPath with package $repoPackage")
            val repoPkgPathStr = repoPackage.replace('.', File.separatorChar)
            val repoPkgPath = repoPath.resolve(repoPkgPathStr)
            Files.createDirectories(repoPkgPath)

            val newFiles = model.generateRepositories(
                repoPkgPath, repositoryBaseClass, repositoryPackage, !generateKotlin) {
                !repoSourceExists(it, repoPkgPathStr)
            }

            val removeFiles = repoPath.listDirectoryEntries().filter {
                it.isRegularFile() && !newFiles.contains(it)
            }
            removeFiles.forEach { it.toFile().delete() }

            compileGeneratedSources(repoPath.toAbsolutePath().toString())
        } catch (mojoErr: MojoFailureException) {
            throw mojoErr
        } catch (e: Exception) {
            log.error("Failed to generate GraphQL schema output", e)
            throw MojoFailureException("Failed to generate GraphQL schema output", e)
        }
    }

    fun repoSourceExists(fileName: String, packagePath: String): Boolean {
        val thisProject = project!!
        return thisProject.compileSourceRoots.any { src ->
            val path = Path(src).resolve(packagePath)
            return path.exists() && path.isDirectory() && path.resolve(fileName).exists()
        }
    }

    fun compileGeneratedSources(genSourceDir: String) {
        val pluginVersion = project!!.properties["compiler-plugin.version"] as String
        log.info("Copy generated resources to ${project!!.build.outputDirectory}")
        executeMojo(
            plugin(
                groupId("org.apache.maven.plugins"),
                artifactId("maven-compiler-plugin"),
                version(pluginVersion)
            ),
            goal("compile"),
            configuration(
                element("compileSourceRoots", genSourceDir)
            ),
            executionEnvironment(project, session, pluginManager)
        )
    }
}