
// Note: This import should not be necessary, so comment it out to test that behavior.
// On the K2 compiler, even when the import is left in the script, the IDE still cannot find it.
//import zk.pub.scripts.definitions.numberThatTheScriptCanAccess

/**
 * This is a very contrived example to show that we can import and call this function from other
 * scripts.
 */
fun plusTwo(
    input: Int
): Int {
    println("hello from the utility script !!!")
  return input + numberThatTheScriptCanAccess
}
