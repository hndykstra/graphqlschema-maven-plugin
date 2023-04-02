package com.opsysinc.graphql.plugin

/**
 * Model of configuration that allows additional scalar mappings to be added in the
 * mojo configuration.
 */
class ScalarMapping {
    var scalarName = ""
    var classes = mutableListOf<String>()
}