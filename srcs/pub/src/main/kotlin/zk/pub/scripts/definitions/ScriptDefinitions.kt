package zk.pub.scripts.definitions

import kotlin.script.experimental.annotations.KotlinScript
import zk.pub.scripts.UserScriptCompilationConfiguration
import zk.pub.scripts.UserScriptEvaluationConfiguration

const val PUB_PACKAGE_NAME = "zk.pub"

const val EXAMPLE_SCRIPT_FILE_EXTENSION = "example.kts"
const val UTILS_SCRIPT_FILE_EXTENSION = "utils.kts"

/**
 * This is a feature of kotlin scripting that I expect the IDE to support. When importing things, we
 * want our users to have an entry point that is maximally intuitive and convenient.
 *
 * This is relative to the launched working directory (which is the repo root).
 */
const val SCRIPT_IMPORTS_ARE_RELATIVE_TO = "assets/"

/**
 * This is a property that we import into the utility script, otherwise we don't have a way of
 * knowing if the IDE support for it is working properly.
 *
 * This import should be available via 'default imports'.
 */
val numberThatTheScriptCanAccess = 2

/**
 * An implementer of this interface is a DEFINITION for a user script. User scripts themselves
 * unfortunately cannot implement an interface (as of Jan 2025). If they could, we would make them
 * implement this function:
 * ```kotlin
 * fun invoke(context: ContextT)
 * ```
 *
 * This is the function that gets called to "do the work" of the script. Here we just declare some
 * additional registration data.
 *
 * A "user" script, and by that we mean that it's something that has code loaded at runtime. These
 * scripts have a "context type" that is the generic parameter, and that type is mutable.
 *
 * Implementers of this class should also use the @KotlinScript annotation (this is what enables IDE
 * support to work). Remember to also add any new script definitions to
 * pub/src/main/resources/META-INF.kotlin.script.templates/...
 */
interface UserScriptDefinition<ContextT> {
    /**
     * The type of the context struct that is passed to the user script in it's invoke() method.
     * Thanks to type erasure, we can't figure it out on our own. :(
     */
    val contextType: Class<ContextT>
}

/** This script imports another script. */
@KotlinScript(
    displayName = "Example Script",
    fileExtension = EXAMPLE_SCRIPT_FILE_EXTENSION,
    evaluationConfiguration = UserScriptEvaluationConfiguration::class,
    compilationConfiguration = UserScriptCompilationConfiguration::class,
)
object ExampleScript : UserScriptDefinition<ExampleScript.Context> {
    override val contextType = Context::class.java

    /** Mutable Context for the script, the script changes this to prove it ran. */
    data class Context(var number: Int)
}

/**
 * This is a script that is imported by other scripts. We still need a compilation configuration for
 * it so it can import and resolve things.
 */
@KotlinScript(
    displayName = "Utility Script",
    fileExtension = UTILS_SCRIPT_FILE_EXTENSION,
    evaluationConfiguration = UserScriptEvaluationConfiguration::class,
    compilationConfiguration = UserScriptCompilationConfiguration::class,
)
object UtilityScript : UserScriptDefinition<Unit> {
    // Context is not used for utility scripts.
    override val contextType = Unit::class.java
}
