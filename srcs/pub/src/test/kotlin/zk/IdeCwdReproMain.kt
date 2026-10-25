package zk

import java.io.File
import kotlin.reflect.full.findAnnotations
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlinx.coroutines.runBlocking
import zk.pub.scripts.definitions.ExampleScript

/**
 * CLI stand-in for what IntelliJ IDEA does when it analyzes a script file: it instantiates the
 * [kotlin.script.experimental.api.ScriptCompilationConfiguration] referenced by the @KotlinScript
 * annotation and compiles the script IN-PROCESS.
 *
 * The key detail is that the IDE process's working directory is wherever the IDE was launched from,
 * NOT the project root. The 'reproIdeScriptAnalysis' Gradle task runs this main with workingDir set
 * outside the repo, which reproduces https://youtrack.jetbrains.com/issue/KTIJ-36841 without
 * needing the IDE:
 * ```
 * ./gradlew :srcs:pub:reproIdeScriptAnalysis
 * ```
 *
 * Usage: IdeCwdReproMain <absolute-path-to-example-script.kts>
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: IdeCwdReproMain <absolute-path-to-example-script.kts>" }

    val scriptFile = File(args[0])
    println("== mimicking IDE in-process script analysis ==")
    println("process CWD     : ${System.getProperty("user.dir")}")
    println("script file     : ${scriptFile.absolutePath} (exists: ${scriptFile.isFile})")

    // Exactly what the IDE does: pull the compilation configuration off the script
    // definition's @KotlinScript annotation and compile with it.
    val annotation = ExampleScript::class.java.kotlin.findAnnotations<KotlinScript>().single()
    val compilationConfiguration = annotation.compilationConfiguration.constructors.single().call()

    val result = runBlocking {
        JvmScriptCompiler()(FileScriptSource(scriptFile), compilationConfiguration)
    }

    when (result) {
        is ResultWithDiagnostics.Success -> {
            println("RESULT: SUCCESS (imported script resolved)")
            result.reports.forEach { println("  [${it.severity}] ${it.message}") }
        }
        is ResultWithDiagnostics.Failure -> {
            println("RESULT: FAILURE (imported script NOT resolved)")
            result.reports.forEach { println("  [${it.severity}] ${it.message}") }
            throw IllegalStateException(
                "Repro failed: the script could not be compiled from a non-project CWD."
            )
        }
    }
}
