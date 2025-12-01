package zk.pub.scripts

import zk.pub.content.registries.UserScriptRegistry
import zk.pub.scripts.definitions.ExampleScript
import zk.pub.util.errorAnnotationWrapper

/** Invokes a script of the 'ExampleScript' type, with the provided input number. */
fun runExampleScript(
    userScripts: UserScriptRegistry,
    pathWithExtension: String,
    inputNumber: Int,
): Int {
    val context = ExampleScript.Context(number = inputNumber)

    errorAnnotationWrapper("When executing script at '$pathWithExtension':") {
        userScripts.getScript<ExampleScript.Context>(pathWithExtension).invoke(context)
    }

    return context.number
}
