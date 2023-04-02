/**
 * Placeholder. This project intends the basic capability:
 * 1) Gaol: generateSchema - Use jandex index to scan for
 * aecm-graphql annotated classes, and generate the schema file
 * and fragment files for the types.
 * <P>
 * ASSUMPTIONS:
 * For now, assume ${project.dir}/classes/META-INF/jandex.idx for input
 *  [later, maybe allow this to scan for index, but this assumes the project uses the jandex plugin]
 * The jandex index will include all the relevant classes (e.g., not scanning for jandex.idx in dependencies)
 *  [assuming the project will incorporate dependencies into the jandex.idx if appropriate.]
 *  [possibly Jandex API will allow this to be relaxed.]
 *  </P>
 */
package com.opsysinc;