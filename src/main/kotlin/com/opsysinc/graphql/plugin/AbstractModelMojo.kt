package com.opsysinc.graphql.plugin

import com.opsysinc.graphql.plugin.model.SchemaModel
import org.apache.maven.artifact.Artifact
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.BuildPluginManager
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.jboss.jandex.CompositeIndex
import org.jboss.jandex.Index
import org.jboss.jandex.IndexReader
import org.jboss.jandex.IndexView
import org.twdata.maven.mojoexecutor.MojoExecutor.artifactId
import org.twdata.maven.mojoexecutor.MojoExecutor.configuration
import org.twdata.maven.mojoexecutor.MojoExecutor.element
import org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo
import org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment
import org.twdata.maven.mojoexecutor.MojoExecutor.goal
import org.twdata.maven.mojoexecutor.MojoExecutor.groupId
import org.twdata.maven.mojoexecutor.MojoExecutor.plugin
import org.twdata.maven.mojoexecutor.MojoExecutor.version
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Enumeration
import java.util.jar.JarEntry
import java.util.jar.JarFile
import kotlin.io.path.Path

abstract class AbstractModelMojo : AbstractMojo() {
    /** Injected as the maven project object. */
    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    var project : MavenProject? = null
    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    var session : MavenSession? = null
    @Component
    var pluginManager : BuildPluginManager? = null


    @Component
    private val repositorySystem: RepositorySystem? = null

    @Parameter(defaultValue = "\${repositorySystemSession}", readonly = true, required = true)
    private val repositorySession: RepositorySystemSession? = null


    /** From the pom file, or default to {baseDir}/target/classes/META-INF/jandex.idx */
    @Parameter(property = "indexFile")
    val indexFile : String? = null
    @Parameter(property = "scanDependencies", required = false)
    val scanDependencies: ScanDependencies? = null
    @Parameter(property = "outputSchemaLocation", defaultValue = "\${project.build.directory}/generated-resources/graphql-schema")
    val outputSchemaLocation : String? = null
    @Parameter(property = "outputQueryLocation", defaultValue = "\${project.build.directory}/generated-resources/graphql-queries")
    val outputQueryLocation : String? = null
    @Parameter(property = "generatedSourceDirectory", defaultValue = "\${project.build.directory}/generated-sources/graphql-repos")
    val sourceOutputLocation : String? = null
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
    @Parameter(property = "queryDirectory", defaultValue = "")
    val queryDirectory = ""
    @Parameter(property = "repositoryDirectory", defaultValue = "")
    val repositoryDirectory = ""
    @Parameter(property = "repositoryBaseClass", defaultValue = "")
    val repositoryBaseClass = ""
    @Parameter(property = "repositoryPackage", defaultValue = "")
    val repositoryPackage = ""
    @Parameter(property = "generateKotlin", defaultValue = "false")
    val generateKotlin = false

    protected fun resolveDependencies(scanDependencies: ScanDependencies?) : List<Artifact> {
        val thisProject = project!!
        val testDeps = scanDependencies?.dependencies
        val dependencies = thisProject.getArtifacts()
        val filteredDependencies = dependencies.filter {
            val match = "${it.groupId}:${it.artifactId}"
            testDeps == null || testDeps.contains(match)
        }
        return filteredDependencies
    }

    protected fun resolveJandexResources(scanArtifacts: List<Artifact>) : List<Index> {
        return scanArtifacts.mapNotNull { resolveJandex(it) }
    }

    protected fun resolveJandex(scanArtifact: Artifact) : Index? {
        var extractedIndex : Index? = null
        if (scanArtifact.getFile() != null && scanArtifact.getFile().getName().endsWith(".jar")) {
            log.debug(java.lang.String.format("Scanning JAR: %s", scanArtifact.getFile().getName()))

            JarFile(scanArtifact.getFile()).use { jarFile ->
                val entries: Enumeration<JarEntry?> = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry: JarEntry = entries.nextElement()!!
                    if (entry.isDirectory()) {
                        continue
                    }

                    val entryName: String = entry.getName()
                    // Check if this entry matches our resource patterns
                    if (entryName == "META-INF/jandex.idx") {
                        extractedIndex = extractIndex(jarFile, entry)
                        if (extractedIndex != null) {
                            log.debug(
                                java.lang.String.format(
                                    "Extracted: %s from %s",
                                    entryName, scanArtifact.getFile().getName()
                                )
                            )
                        }
                    }
                }
            }
        }

        return extractedIndex
    }

    protected fun extractIndex(jarFile: JarFile, jarEntry: JarEntry) : Index? {
        jarFile.use {
            it.getInputStream(jarEntry).use { inStream ->
                // read the index file from the JAR to memory
                return IndexReader(inStream).read()
            }
        }
    }

    protected fun setupProjectClassloader(p: MavenProject) : ClassLoader {
        val classpathUrls = mutableListOf<URL>()
        p.compileClasspathElements.forEach { element ->
            classpathUrls.add(Path(element as String).toUri().toURL())
        }
        p.runtimeClasspathElements.forEach { element ->
            classpathUrls.add(Path(element as String).toUri().toURL())
        }

        return URLClassLoader(classpathUrls.toTypedArray())
    }

    protected fun createSchemaScanner() : SchemaGeneration {
        val thisProject = project ?: throw MojoFailureException("Project is null")
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

        val allJandex = mutableListOf<IndexView>(jandex)
        if (scanDependencies?.dependencies != null) {
            val scanArtifacts = resolveDependencies(scanDependencies)
            allJandex.addAll(resolveJandexResources(scanArtifacts))
        }

        val indexView: IndexView = if (allJandex.size > 1) CompositeIndex.create(allJandex) else jandex
        val model = SchemaModel(log, includeNeo4jScalars)
        scalarMappings.forEach { model.addScalarMapping(it) }
        return SchemaGeneration(log, indexView, model, setupProjectClassloader(workingProject))
    }

    protected fun copyResources(fromPath: String, overwrite: Boolean = false) {
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
                        element("directory", fromPath)
                    )
                ),
                element("overwrite", overwrite.toString())
            ),
            executionEnvironment(project, session, pluginManager)
        )
    }
}