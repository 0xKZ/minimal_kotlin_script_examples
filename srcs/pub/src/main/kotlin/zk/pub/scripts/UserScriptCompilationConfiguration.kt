package zk.pub.scripts

import java.io.File
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import zk.pub.scripts.definitions.PUB_PACKAGE_NAME
import zk.pub.scripts.definitions.SCRIPT_IMPORTS_ARE_RELATIVE_TO

/**
 * Helps cover the imports and rules we think all scripts can share.
 *
 * We make it a simple parameterless class so that when we embed it in the annotation, the IDE
 * doesn't have to do anything complicated to instantiate it.
 */
class UserScriptCompilationConfiguration : ScriptCompilationConfiguration(compileConfigBuilder)

private val compileConfigBuilder: ScriptCompilationConfiguration.Builder.() -> Unit = {
    ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }

    defaultImports(
        "kotlin.math.*",
        "$PUB_PACKAGE_NAME.*",
        "$PUB_PACKAGE_NAME.util.*",
        "$PUB_PACKAGE_NAME.scripts.definitions.*",
        // ...
    )

    // From the kotlin-script-examples repo, I pulled a minimal set of things to enable
    // importing scripts from other scripts:
    defaultImports(Import::class)
    refineConfiguration { onAnnotations(Import::class, handler = ImportHandler()) }

    // Workaround to cover that when we are in a built JVM, there's apparently no context
    // classloader, but the kotlin scripting system expects it to exist, so we hack it in there.
    if (Thread.currentThread().contextClassLoader == null) {
        println("Overriding the context class loader for scripts because one was not set!")
        Thread.currentThread().contextClassLoader = ClassLoader.getSystemClassLoader()
    }

    // Scripts operate in a module of mine called 'pub' (short for 'public'). I want the scripts
    // to be able to access anything in 'pub':
    jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
}

/**
 * Same as the compilation configuration, but for evaluation time. Note that it's crucial that we do
 * NOT make the context parameter appear anywhere here (e.g, as a constructor parameter or an
 * implicit receiver), because that is a runtime varying parameter, and we want to make sure that
 * the execution of the script happens in a later, separate stage from the evaluation. This is the
 * best way I have found to have a reasonable performance for the scripts (running the evaluator is
 * very slow!)
 */
class UserScriptEvaluationConfiguration : ScriptEvaluationConfiguration(evalConfigBuilder)

private val evalConfigBuilder: ScriptEvaluationConfiguration.Builder.() -> Unit = {
    // This sounds great, but I don't think this parameter works in practice because the
    // performance is still so bad that I am doing my own caching with the instances. Leaving
    // it enabled in hopes it magically works on a kotlin update in the future!
    scriptsInstancesSharing(true)
}

/**
 * Import other script(s).
 *
 * It looks like we have to manually declare this class:
 * https://github.com/Kotlin/kotlin-script-examples/blob/fec834e2a9a2c07c8486a684d3e131cb909015d7/jvm/simple-main-kts/simple-main-kts/src/main/kotlin/org/jetbrains/kotlin/script/examples/simpleMainKts/annotations.kt#L20
 */
@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class Import(vararg val paths: String)

// Modified from
// https://github.com/Kotlin/kotlin-script-examples/blob/master/jvm/simple-main-kts/simple-main-kts/src/main/kotlin/org/jetbrains/kotlin/script/examples/simpleMainKts/scriptDef.kt
private class ImportHandler : RefineScriptCompilationConfigurationHandler {
    override operator fun invoke(
        context: ScriptConfigurationRefinementContext
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> = processAnnotations(context)

    private fun processAnnotations(
        context: ScriptConfigurationRefinementContext
    ): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val annotations =
            context.collectedData?.get(ScriptCollectedData.foundAnnotations)?.takeIf {
                it.isNotEmpty()
            } ?: return context.compilationConfiguration.asSuccess()

        // If instead we wanted to make it relative to the current script file, we would do that
        // like so: `(context.script as? FileBasedScriptSource)?.file?.parentFile`.
        val scriptBaseDir = File(SCRIPT_IMPORTS_ARE_RELATIVE_TO)
        val importedSources =
            annotations.flatMap {
                (it as? Import)?.paths?.map { sourceName ->
                    FileScriptSource(scriptBaseDir.resolve(sourceName))
                } ?: emptyList()
            }

        return ScriptCompilationConfiguration(context.compilationConfiguration) {
                if (importedSources.isNotEmpty()) importScripts.append(importedSources)
            }
            .asSuccess()
    }
}
