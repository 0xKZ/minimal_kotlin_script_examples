
// Note: This import should not be necessary, it's commented out if all is well.
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
