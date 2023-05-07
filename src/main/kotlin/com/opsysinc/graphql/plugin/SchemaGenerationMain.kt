package com.opsysinc.graphql.plugin

import com.opsysinc.graphql.plAugin.ModelException
import com.opsysinc.graphql.plugin.model.SchemaModel
import org.jboss.jandex.IndexReader
import org.jboss.jandex.IndexView
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    SchemaGenerationMain(args).run()
}

class SchemaGenerationMain (args: Array<String>) {
    private val projectClasspathToScan : List<String>
    private val outputDir : Path
    init {
        // for simplicity, not parsing args much
        // use environment
        projectClasspathToScan = (System.getProperty("project.classpath") ?: "")
            .split(File.pathSeparator)
        outputDir = Path(System.getProperty("schema.output") ?: System.getProperty("user.dir") ?: ".")
    }

    fun run() {
        Files.createDirectories(outputDir)
        val pathUrls = projectClasspathToScan
            .map { name -> File(name).toURI().toURL() }
            .toTypedArray()
        val loader = URLClassLoader(pathUrls)
        val indexUrl = loader.getResource("META-INF/jandex.idx")
        if (indexUrl == null) {
            println("Unable to find jandex index file")
            return
        }
        val indexView : IndexView = indexUrl.openStream().use {
            val rdr = IndexReader(it)
            rdr.read()
        }

        val schema = SchemaModel()

        val generator = SchemaGeneration(indexView, schema, loader)

        val allErrors = mutableListOf<ModelException>()
        allErrors.addAll(generator.scan())

        allErrors.addAll(schema.validate())

        allErrors.forEach { err ->
            logger.error(err.message)
        }

        val schemaFile = outputDir.resolve("schema.graphql")
        println("Output to ${schemaFile.normalize()}")
        schema.generateSchema(schemaFile)
        schema.generateFragments(outputDir)
    }

    companion object {
        val logger = LoggerFactory.getLogger(SchemaGenerationMain::class.java)
    }
}
