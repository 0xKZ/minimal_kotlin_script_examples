package zk.pub.util

import java.lang.RuntimeException

/** Like errorAnnotationWrapperLazy(), but for convenience takes in an already-generated string. */
inline fun <R> errorAnnotationWrapper(
    messagePrefix: String,
    // Optional function to do any predefined cleanup before re-throwing
    crossinline errorCleanupFn: () -> Unit = {},
    crossinline body: () -> R,
): R {
    return errorAnnotationWrapperLazy(
        messagePrefixFn = { messagePrefix },
        errorCleanupFn = errorCleanupFn,
        body = body,
    )
}

/**
 * Wraps some code, and replaces the error message with this error message and re-throws. Useful for
 * exceptions that we end up showing to the player that have to do with specific content bits that
 * are referencing other content bits.
 *
 * builds the string lazily in case the messagePrefix is expensive to compute.
 */
inline fun <R> errorAnnotationWrapperLazy(
    messagePrefixFn: () -> String,
    // Optional function to do any predefined cleanup before re-throwing
    crossinline errorCleanupFn: () -> Unit = {},
    crossinline body: () -> R,
): R {
    return try {
        body()
    } catch (e: Exception) {
        errorCleanupFn()
        throw RuntimeException("${messagePrefixFn()} ${e.message ?: ""}", e)
    }
}
