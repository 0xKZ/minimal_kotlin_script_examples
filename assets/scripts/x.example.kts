@file:Import("utility_scripts/x.utils.kts")

fun invoke(context: ExampleScript.Context) {
    // Use our extremely complicated imported function to do the heavy lifting
    context.number = plusTwo(context.number)
}
