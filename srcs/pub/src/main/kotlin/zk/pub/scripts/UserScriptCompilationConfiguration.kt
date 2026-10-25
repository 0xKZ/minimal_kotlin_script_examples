package zk.pub.scripts

import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import zk.pub.scripts.definitions.ExampleScript
import zk.pub.scripts.definitions.PUB_PACKAGE_NAME
import zk.pub.scripts.definitions.SCRIPT_IMPORTS_ARE_RELATIVE_TO
import zk.pub.scripts.definitions.UtilityScript

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
        // When -Dzk.debug.scriptImports=true, dump the configuration the host (IDE or JVM)
        // built for the matched script definition, plus where the definition classes were
        // loaded from. This is what lets us see the exact fileExtension the IDE uses for
        // matching and for the "Script definition: ..." code vision inlay.
        debugScriptDefinition(context)

        val annotations =
            context.collectedData?.get(ScriptCollectedData.foundAnnotations)?.takeIf {
                it.isNotEmpty()
            } ?: return context.compilationConfiguration.asSuccess()

        // If instead we wanted to make it relative to the current script file, we would do that
        // like so: `(context.script as? FileBasedScriptSource)?.file?.parentFile`.
        val scriptBaseDir = resolveScriptImportsBaseDir()
        val importedSources =
            annotations.flatMap {
                (it as? Import)?.paths?.map { sourceName ->
                    FileScriptSource(scriptBaseDir.resolve(sourceName))
                } ?: emptyList()
            }
        importedSources.forEach {
            debugScriptImports("Import source resolved to: ${it.file} (exists=${it.file.exists()})")
        }

        return ScriptCompilationConfiguration(context.compilationConfiguration) {
                if (importedSources.isNotEmpty()) importScripts.append(importedSources)
            }
            .asSuccess()
    }
}

/**
 * Resolves the directory that [Import] paths are relative to, as an ABSOLUTE path.
 *
 * Why absolute: the Kotlin scripting system knows nothing about the project or its root (this is
 * exactly what the JetBrains team pointed out in https://youtrack.jetbrains.com/issue/KTIJ-36841).
 * When IntelliJ analyzes a script, this handler runs in the IDE process, whose working directory is
 * wherever the IDE was launched from (on macOS: `/`), not the project root, so a CWD-relative base
 * only works by luck (e.g. in tests, where the working directory is standardized on the repo root).
 * Their suggested fix, implemented here: define the assets directory as an absolute path and build
 * the (relative) @Import paths from it.
 *
 * Resolution order:
 * 1. Explicit override: `-Dzk.assetsDir=<absolute path>` system property or the `ZK_ASSETS_DIR`
 *    environment variable. This is the manual version of the JetBrains suggestion; use it when
 *    auto-discovery cannot apply (e.g. this library is consumed as a jar from a dependency cache
 *    with no project directories on the classpath at all).
 * 2. Discovery by walking up from on-disk locations this library may be loaded from: a. The
 *    directory this class is loaded from, when it is loaded from a directory classpath (Gradle
 *    tests, the IDE's compiled-classes output like `<root>/srcs/pub/build/classes/kotlin/main`,
 *    ...). b. Every `file:` DIRECTORY entry on the classpath of the loader that loaded this class
 *    (in classpath order). This is what makes the IDE case work: the IDE loads this library from
 *    its published jar, so the class itself has no on-disk location, but the IDE's "Classpath for
 *    loading script definitions" typically also lists the project's own compiled classes, and
 *    walking up from those directories still reaches the project root. c. (Weaker signal, checked
 *    last) The parent directories of `jar:` entries: the directory this class is loaded from (when
 *    loaded from a jar) first, then every other jar's parent directory. Useful when the library jar
 *    sits inside the project; harmless (and fruitless) when it sits in a dependency cache like
 *    ~/.m2.
 * 3. Legacy fallback: CWD-relative [SCRIPT_IMPORTS_ARE_RELATIVE_TO]. Only correct when the
 *    process's working directory is the project root (e.g. an app launched from its root).
 */
private fun resolveScriptImportsBaseDir(): File {
    val override = System.getProperty("zk.assetsDir") ?: System.getenv("ZK_ASSETS_DIR")
    if (override != null) {
        val file = File(override)
        check(file.isAbsolute) {
            "zk.assetsDir / ZK_ASSETS_DIR must be an absolute path, but was: $override"
        }
        debugScriptImports("Using explicit scripts base dir override: $file")
        return file
    }

    // Candidate roots, in priority order (stronger signals first):
    // (1) Where is this class loaded from?
    //   - directory classpath:
    // `<classesDir>/zk/pub/scripts/UserScriptCompilationConfiguration.class`
    //   - jar: the jar file's parent directory (a weaker signal, so it is queued after all
    //     directory-based candidates below)
    val directoryStarts = linkedSetOf<File>()
    val jarStarts = linkedSetOf<File>()
    val classUrl =
        UserScriptCompilationConfiguration::class
            .java
            .getResource("UserScriptCompilationConfiguration.class")
    classUrl?.let { url ->
        debugScriptImports("This class is loaded from: $url")
        when (url.protocol) {
            "file" ->
                runCatching { File(url.toURI()) }
                    .getOrNull()
                    ?.parentFile
                    ?.let { directoryStarts += it }
            "jar" -> runCatching { jarFile(url) }.getOrNull()?.parentFile?.let { jarStarts += it }
        }
    }

    // (2) The on-disk entries on the classpath of the loader that loaded this class, in
    // classpath order. Directory entries are strong candidates; jar entries are weak ones.
    // (Their parent directory is used because a jar file has no children to walk down.)
    val loader = UserScriptCompilationConfiguration::class.java.classLoader
    for (entry in classpathEntries(loader)) {
        when {
            entry.isDirectory -> directoryStarts += entry
            entry.isFile -> entry.parentFile?.takeIf { it.isDirectory }?.let { jarStarts += it }
        }
    }
    debugScriptImports(
        "Base-dir candidates: directories=$directoryStarts jar-parents=$jarStarts " +
            "(classLoader=${loader.javaClass.name})"
    )

    // Walk up from each candidate root (in priority order) until an `assets` folder is found.
    val visited = mutableSetOf<String>()
    for (start in directoryStarts + jarStarts) {
        var dir: File? = start
        while (dir != null && visited.add(dir.absolutePath)) {
            val assets = dir.resolve("assets")
            if (assets.isDirectory) {
                debugScriptImports("Discovered scripts base dir from $start: $assets")
                return assets
            }
            dir = dir.parentFile
        }
    }

    val fallback = File(SCRIPT_IMPORTS_ARE_RELATIVE_TO)
    debugScriptImports(
        "Falling back to CWD-relative scripts base dir: $fallback " +
            "(CWD=${System.getProperty("user.dir")})"
    )
    return fallback
}

/**
 * The on-disk entries (files and directories) on the classpath of [classLoader], in classpath
 * order.
 *
 * Classpath entry discovery, in order:
 * 1. `java.net.URLClassLoader.getURLs()` (kotlin scripting hosts, Gradle tests, ...).
 * 2. A public no-arg `getUrls()`/`getURLs()` method on the loader, invoked reflectively. This is
 *    the key for the IDE: IntelliJ loads script definitions with its own
 *    `com.intellij.util.lang.UrlClassLoader`, which does NOT extend `java.net.URLClassLoader` but
 *    does expose its entries via `getUrls()`.
 * 3. The `java.class.path` system property (the JVM's application classpath; the JDK's internal app
 *    loader exposes no URLs).
 *
 * Unparseable entries are skipped.
 */
private fun classpathEntries(classLoader: ClassLoader): List<File> {
    val urls = loaderClasspathUrls(classLoader)
    debugScriptImports("ClassLoader ${classLoader.javaClass.name} classpath: $urls")
    return urls.mapNotNull { url ->
        when (url.protocol) {
            "file" -> runCatching { File(url.toURI()) }.getOrNull()
            "jar" -> runCatching { jarFile(url) }.getOrNull()
            else -> null
        }
    }
}

/** The classpath entry URLs of [classLoader], see [classpathEntries] for the discovery order. */
private fun loaderClasspathUrls(classLoader: ClassLoader): List<URL> {
    if (classLoader is URLClassLoader) return classLoader.getURLs().toList()

    for (methodName in listOf("getUrls", "getURLs")) {
        val result =
            runCatching { classLoader.javaClass.getMethod(methodName).invoke(classLoader) }
                .getOrNull()
        val urls =
            when (result) {
                is Array<*> -> result.toList()
                is List<*> -> result
                else -> continue
            }
        if (urls.isNotEmpty() && urls.all { it is URL }) {
            debugScriptImports(
                "Retrieved classpath from ${classLoader.javaClass.name} via $methodName()"
            )
            @Suppress("UNCHECKED_CAST")
            return urls as List<URL>
        }
    }

    debugScriptImports(
        "ClassLoader ${classLoader.javaClass.name} exposes no URLs; " +
            "falling back to java.class.path=${System.getProperty("java.class.path")}"
    )
    val classPath = System.getProperty("java.class.path") ?: return emptyList()
    return classPath
        .split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .mapNotNull { runCatching { File(it).toURI().toURL() }.getOrNull() }
}

/**
 * The jar file referenced by a `jar:` protocol [url] (e.g.
 * `jar:file:/path/pub-0.1.0.jar!/zk/pub/scripts/Foo.class` -> `/path/pub-0.1.0.jar`).
 */
private fun jarFile(url: URL): File =
    (url.openConnection() as? JarURLConnection)?.jarFileURL?.let { File(it.toURI()) }
        ?: error("Not a jar: URL: $url")

/**
 * Debug diagnostics for the IDE matching/inlay issue, printed when `-Dzk.debug.scriptImports=true`
 * is set on the running JVM (the IDE: add it to Help | Edit Custom VM Options).
 *
 * Prints, for the script currently being refined:
 * - the compilation configuration the host built for the matched definition: its fileExtension (the
 *   EXACT value the "Script definition: ..." code vision inlay displays) and the rest of the stored
 *   properties;
 * - where each definition class was actually LOADED FROM (codeSource) and what its @KotlinScript
 *   annotation says at runtime.
 *
 * Together these answer: does the IDE's in-memory definition carry fileExtension="example.kts" (in
 * which case a ".kts" inlay is a display/caching issue), or "kts" (in which case a stale class was
 * loaded, and the codeSource line says from where)?
 */
private fun debugScriptDefinition(context: ScriptConfigurationRefinementContext) {
    if (!System.getProperty("zk.debug.scriptImports", "false").toBoolean()) return
    val configuration = context.compilationConfiguration
    debugScriptImports(
        "Definition config for script=${context.script.locationId ?: context.script.name ?: "<unnamed>"}: " +
            "fileExtension=${configuration[ScriptCompilationConfiguration.fileExtension]}, " +
            "displayName=${configuration[ScriptCompilationConfiguration.displayName]}, " +
            "filePathPattern=${configuration[ScriptCompilationConfiguration.filePathPattern]}"
    )
    for ((key, value) in configuration.entries()) {
        val text = value?.toString()?.let { if (it.length > 120) it.take(120) + "..." else it }
        debugScriptImports(
            "  config[${key.name}] = $text (${value?.let { it::class.java.name } ?: "null"})"
        )
    }
    // The "Script definition: ..." inlay displays
    // `kotlinScriptTemplate?.title ?: ".${fileExtension ?: "kts"}"`.
    // The template key is IDE-side (not part of the kotlin scripting API), so it can only be
    // observed through the stored entries: absent (the normal case for user definitions) means
    // title=null and the inlay is exactly ".${fileExtension}".
    val templateEntry =
        configuration.entries().firstOrNull { it.key.name == "kotlinScriptTemplate" }
    debugScriptImports(
        "Inlay inputs: title=${templateEntry?.value?.toString() ?: "<template key not set -> null>"}, " +
            "inlayText=Script definition: .${configuration[ScriptCompilationConfiguration.fileExtension] ?: "kts"}"
    )
    debugScriptDefinitionClass(ExampleScript::class.java)
    debugScriptDefinitionClass(UtilityScript::class.java)
}

/** Debug: where [kclass] was loaded from, and what its @KotlinScript annotation says at runtime. */
private fun debugScriptDefinitionClass(kclass: Class<*>) {
    val annotation = kclass.annotations.firstOrNull { it is KotlinScript } as? KotlinScript
    debugScriptImports(
        "Definition class ${kclass.name}: loadedFrom=${kclass.protectionDomain?.codeSource?.location}, " +
            "loader=${kclass.classLoader?.javaClass?.name}, " +
            "annotation.fileExtension=${annotation?.fileExtension}, " +
            "annotation.displayName=${annotation?.displayName}"
    )
}

/**
 * Logs [message] to stdout AND appends it to a debug file when `-Dzk.debug.scriptImports=true` is
 * set on the running JVM.
 *
 * In the IDE, stdout is captured into idea.log as `INFO - STDOUT` lines; the file append is a
 * backup in case that changes. File path defaults to `/tmp/zk-script-debug.log` and can be
 * overridden with `-Dzk.debug.scriptImports.file=<path>`.
 */
private fun debugScriptImports(message: String) {
    if (!System.getProperty("zk.debug.scriptImports", "false").toBoolean()) return
    val line = "[zk.scripting] [${System.currentTimeMillis()}] $message"
    println(line)
    try {
        val file =
            File(System.getProperty("zk.debug.scriptImports.file", "/tmp/zk-script-debug.log"))
        file.parentFile?.mkdirs()
        file.appendText(line + System.lineSeparator())
    } catch (_: Throwable) {
        // Debug logging must never break import resolution.
    }
}
