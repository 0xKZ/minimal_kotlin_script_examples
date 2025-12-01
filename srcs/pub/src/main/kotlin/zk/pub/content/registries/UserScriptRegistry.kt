package zk.pub.content.registries

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.findAnnotations
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import zk.pub.scripts.definitions.ExampleScript
import zk.pub.scripts.definitions.UserScriptDefinition
import zk.pub.scripts.definitions.UtilityScript
import zk.pub.util.errorAnnotationWrapper

/**
 * Represents a script that has already been compiled and evaluated, and is ready to execute as
 * though it were a function.
 *
 * "Any" here is actually the 'ContextT' of the script class. These are compiled all together so we
 * unfortunately can't store them in a type safe manner, so we check them at runtime.
 */
private typealias TypeErasedScriptLambda = (Any) -> Unit

/** Manages compiling and storing user scripts for later execution */
open class UserScriptRegistry {

    /** A convenient repackaging of various information about a script that we can easily access. */
    private data class RegisteredScriptType(
        // This is the annotation from the definition class, which holds the compilation
        // configuration, file extension, and other things.
        val kotlinScriptAnnotation: KotlinScript,

        // The annotation only stores the classes of the compilation and evaluation configurations,
        // so we just store an instantiated copy here so we don't have to do that over and over.
        val compilationConfiguration: ScriptCompilationConfiguration,
        val evaluationConfiguration: ScriptEvaluationConfiguration,

        // This holds the context data type which we care about
        val definition: UserScriptDefinition<*>,
    ) {
        companion object {
            fun fromDefinition(definition: UserScriptDefinition<*>): RegisteredScriptType {
                val annotations = definition.javaClass.kotlin.findAnnotations<KotlinScript>()
                check(annotations.size == 1) {
                    "Expected exactly one @KotlinScript annotation for definition ${definition::class.java}, but saw ${annotations.size}"
                }
                val anno = annotations.first()
                return RegisteredScriptType(
                    kotlinScriptAnnotation = anno,
                    // From my limited understanding, for IDE support we already have to make these
                    // trivially instantiate-able for the annotation, so by instantiating it the
                    // same way here, I am trying to make it so that the IDE's understanding of
                    // the script cannot drift away from the embedded compiler's understanding at
                    // runtime.
                    compilationConfiguration =
                        anno.compilationConfiguration.constructors.single().call(),
                    evaluationConfiguration =
                        anno.evaluationConfiguration.constructors.single().call(),
                    definition = definition,
                )
            }
        }
    }

    /**
     * We "register" script types so that we can pull various data for validating them. A key here
     * is the two-part file type suffix. For example, a map generation script here will be under the
     * key "x.genmap.kts".
     */
    private val scriptFilenamesToTypes: Map<String, RegisteredScriptType> =
        listOf(
                ExampleScript,
                UtilityScript,
                // ... more here?
                //
                // NOTE: If you're adding a new type of script, remember to also add the new
                // `.classname`
                // touchfile to the script templates folder for the IDE to be able to find the
                // definition!
            )
            .associate { definition ->
                val registeredType = RegisteredScriptType.fromDefinition(definition)

                // Stick the prefixes on there so that when we do lookups we don't have to do string
                // logic and can just use the file path directly
                val finalKey =
                    SCRIPT_NAME_PREFIX + registeredType.kotlinScriptAnnotation.fileExtension

                finalKey to registeredType
            }

    // By default it has no cache. Maybe later they'll make one, until then we gotta do our own.
    private val compiler = JvmScriptCompiler()

    // As far as I can tell, evaluator is thread safe because it doesn't seem to store any state...
    private val evaluator = BasicJvmScriptEvaluator()

    // Map of the RELATIVE script path from the asset folder root (because why spend extra compute
    // time if we don't have to for absolute paths) to the future that returns the script, packaged
    // as a lambda for easy invocation. These are compiled asynchronously which is why we are
    // storing the deferred result.
    private val compiledScripts = ConcurrentHashMap<String, Deferred<TypeErasedScriptLambda>>()

    /**
     * Asynchronously reads the script file and compiles it, for later evaluation. Example script
     * path would be "scripts/$scriptName.kts".
     *
     * This can handle absolute paths if you provide an absolute path.
     */
    fun loadScript(scriptPathWithExtension: String) {
        // Ensure nobody goofed on the path. We require this on input rather than just "fixing" it
        // for the caller, because if the caller uses the same string to access the script later
        // (in a performance sensitive context), we don't want them doing path conversions there
        check(!File(scriptPathWithExtension).isAbsolute) {
            "Script loading must be done with paths relative to the asset root for consistency."
        }

        // No double-loading:
        check(compiledScripts[scriptPathWithExtension] == null) {
            "Trying to load a user script that has already been loaded: $scriptPathWithExtension"
        }

        // Compile the script asynchronously and store the Future (technically a Deferred) in the
        // map:
        //
        // TODO read about / remove this from GlobalScope, that's supposedly bad, but not the
        //      focus of this repo...
        val compiledDeferred =
            GlobalScope.async {
                loadScriptInternal(scriptPathWithExtension = scriptPathWithExtension)
            }
        compiledScripts[scriptPathWithExtension] = compiledDeferred
    }

    private suspend fun loadScriptInternal(
        scriptPathWithExtension: String
    ): TypeErasedScriptLambda {
        val registeredType = getScriptTypeFromFullPath(scriptPathWithExtension)

        // There are 3 stages to a script, generally. Compile -> Evaluate (now we have a class
        // instance) -> Execute (by calling a method on the instance). The "Evaluate" stage executes
        // the script body, but it is basically dumping that code into the body of the script class.
        // If we wrote our script directly there, we would effectively be running our code in the
        // constructor of the class. The performance cost of this is actually bad not because of the
        // code being in the constructor, but because of how the evaluator loads the code into a
        // class. The real reason we split the execution into a third phase by requiring a function
        // is so that we can pay this cost only once up front.
        val compiledScript = compileScript(scriptPathWithExtension)

        // Script compiled fine, now time to evaluate. This actually runs the code in the
        // script class, which SHOULD only be computing one-time cached things and declaring
        // the function we need:
        val evaluationResult =
            unwrapScriptEvaluationOutput(
                runBlocking {
                    evaluator(
                        compiledScript = compiledScript,
                        scriptEvaluationConfiguration = registeredType.evaluationConfiguration,
                    )
                }
            )

        // We assert presence here because we would have thrown an exception by here if it wasn't a
        // successful result, which I think is the only way they'll be missing?
        val scriptInstance =
            evaluationResult.returnValue.scriptInstance
                ?: error("Failed to extract script instance from evaluated script?")

        // Now we aren't done yet, because the user could have forgotten to declare the method
        // we need, or declared it wrong. At the time of writing this (Jan 2025) there is no way
        // to make a script conform to an interface, so we do it via reflection checks.
        return errorAnnotationWrapper(
            "When validating script instance ('$scriptPathWithExtension'):"
        ) {
            prepareScriptInstanceForExecution(
                scriptInstance = scriptInstance,
                contextClass = registeredType.definition.contextType,
            )
        }
    }

    /**
     * Validates the script and packages the instance into a lambda that can be more easily used
     * with the context type.
     */
    private fun prepareScriptInstanceForExecution(
        scriptInstance: Any,
        // This is the 'ContextT' on the userscript. The returned lambda is of this type as input,
        // but we have to write 'Any' here because of type erasure (boo).
        contextClass: Class<*>,
    ): TypeErasedScriptLambda {
        val methodName = "invoke"
        val methods = scriptInstance.javaClass.methods.filter { it.name == methodName }

        // Enforce exactly one, give a useful error message in either direction
        check(methods.isNotEmpty()) {
            "Couldn't find the $methodName() method in the script (class: ${scriptInstance.javaClass})."
        }
        check(methods.size == 1) {
            "Expected exactly one $methodName() method in the script, but found ${methods.size}! (class: ${scriptInstance.javaClass})."
        }
        val invokeFn = methods[0]
        check(invokeFn.parameterCount == 1) {
            "Expected exactly one parameter for the $methodName() method in the script, but found ${invokeFn.parameterCount}!"
        }
        val paramType = invokeFn.parameters[0].type
        check(paramType == contextClass) {
            "Parameter type for 'context' in $methodName(context) should be '${contextClass}', but was: '${paramType}'"
        }
        return { context -> methods[0].invoke(scriptInstance, context) }
    }

    /**
     * Blows up if the script blew up (for now, will need to communicate it better to the player
     * later)
     */
    private fun unwrapScriptEvaluationOutput(
        result: ResultWithDiagnostics<EvaluationResult>
    ): EvaluationResult {
        when (result) {
            is ResultWithDiagnostics.Failure ->
                run {
                    val msg = "Script evaluation failed: ${result.reports}"
                    println(msg)
                    result.reports.mapNotNull { it.exception }.firstOrNull()?.let { throw it }
                        ?: error(msg)
                }
            is ResultWithDiagnostics.Success -> {
                return result.value
            }
        }
    }

    private fun getScriptTypeFromFullPath(scriptPathWithExtension: String): RegisteredScriptType {
        val scriptFile = File(scriptPathWithExtension)
        val lookupName = scriptFile.name
        return scriptFilenamesToTypes[lookupName]
            ?: run {
                error(
                    "Unknown script extension type '$lookupName' (seen at '${scriptPathWithExtension}'). Expected one of these recognized script types: {${scriptFilenamesToTypes.keys}}"
                )
            }
    }

    private suspend fun compileScript(scriptPathWithExtension: String): CompiledScript {
        val scriptFile = File(scriptPathWithExtension)
        val lookupName = scriptFile.name

        val cfg =
            scriptFilenamesToTypes[lookupName]?.compilationConfiguration
                ?: throw IllegalArgumentException(
                    "Unknown user script type \"${lookupName}\", from ${scriptFile.path}"
                )
        val source = scriptFile.toScriptSource()

        // Here is where my code would validate the script text to ensure I am not leaking
        // available imports to people that they shouldn't be using.
        // ...

        val result = compiler(source, cfg)

        return result.valueOrNull()
            ?: run {
                // There are a lot of debug messages by default, we don't want to print those.
                val warningsAndErrors =
                    result.reports.filter {
                        it.isError() || it.severity == ScriptDiagnostic.Severity.WARNING
                    }
                error("Compilation of user script (${scriptFile.path}) failed: $warningsAndErrors")
            }
    }

    /** Returns you a lambda that is ready to execute as the script. */
    fun <ContextT : Any> getScript(scriptPathWithExtension: String): (ContextT) -> Unit {
        check(!File(scriptPathWithExtension).isAbsolute) {
            "Trying to access a script from an absolute path. Scripts are stored by relative path."
        }

        val out =
            compiledScripts[scriptPathWithExtension]
                ?: error(
                    "Requesting a script that has never been compiled: $scriptPathWithExtension"
                )

        // Block on finishing compiling, if necessary:
        return runBlocking { out.await() }
    }

    companion object {
        /**
         * Because of IDE limitations, to support syntax highlighting and whatnot we need to
         * differentiate our script templates by file suffix. Unfortunately, that suffix cannot be
         * the complete name of the file, or it won't work. And if we just leave a '.' on the front,
         * many OS's are going to mark it as a hidden file. It's not a good idea to let users name
         * it whatever they want, becuase then we need a lot of extra logic for matching and
         * tracking filenames. If we allow only one file name for scripts, it simplifies our logic.
         *
         * Include the dot so it's harder to mess up combining w/ suffixes. The suffix can't have a
         * dot because we use those directly in the kotlin script annotations for script file
         * extensions.
         */
        val SCRIPT_NAME_PREFIX = "x."
    }
}
