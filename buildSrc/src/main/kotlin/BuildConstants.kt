object BuildConstants {
    /**
     * JVM arguments used for both tests and the final desktop build.
     */
    val BASE_JVM_ARGS: List<String> = listOf(
        "-XX:+HeapDumpOnOutOfMemoryError"
    )

    /**
     * Mac-Only JVM args
     */
    val MACOS_JVM_ARGS: List<String> = listOf(
    )

    /**
     * JVM arguments used in the final built binary, but not in tests.
     */
    val APP_JVM_ARGS: List<String> = BASE_JVM_ARGS + listOf(
    )

    /**
     * JVM arguments used when we run tests, but not in the final built binary.
     */
    val TEST_JVM_ARGS: List<String> = BASE_JVM_ARGS + listOf(
        // This prevents a warning from being printed when you run tests via intelliJ.
        "-XX:+EnableDynamicAgentLoading"
    )
}