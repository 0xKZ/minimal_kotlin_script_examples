# KTIJ-36841 deep-dive: making `@file:Import` resolvable in the IDE

The short version is in the [README](README.md). This is the full story: what the IDE
actually does, why the straightforward implementation of JetBrains' suggestion failed, what
the fix is, why the workaround is unreasonable, and how to verify it without an IDE.

## The problem

`assets/scripts/x.example.kts` contains `@file:Import("utility_scripts/x.utils.kts")` and
uses the imported `plusTwo(...)`. The script compiles and runs fine via `./gradlew test`
and the CLI, but the IDE underlines the import in red: "Imported source file not found".
That is [KTIJ-36841](https://youtrack.jetbrains.com/issue/KTIJ-36841) ("IDE cannot find
imports where compiler can"), reported Dec 2025.

## JetBrains' answer (Oct 25, 2026)

Vlad Koshkin (JetBrains) reproduced the issue in 2026.1 EAP and replied:

> kotlin script knows nothing about the project and its root, so the only possible solution
> would be using an absolute path somewhere; for instance, you can define assets absolute
> path and build other relative script paths from this. or you can wrap your library into
> the ide plugin, and then you'll have access to the project root path.

This repo implements the first suggestion. (The second - wrapping the script library into an
IDE plugin - was rejected as too heavy for a plain library.)

## Why the straightforward implementation fails in the IDE

The `@Import` handling lives in a `RefineScriptCompilationConfigurationHandler`
(`ImportHandler`) in `UserScriptCompilationConfiguration.kt`. Relative import paths have to
be resolved against *some* base directory. The natural choice is the process working
directory (CWD): on the CLI, `kotlinc -script` is usually run from the project root, so
`assets/...` just works.

In the IDE, the handler runs **inside the IDE process**, whose working directory is where
the IDE was launched from - on macOS that is `/`. The Nov 3 log (before the fix) shows
exactly this:

```
[zk.scripting] ClassLoader com.intellij.util.lang.UrlClassLoader is not a URLClassLoader;
              falling back to java.class.path=/Applications/IntelliJ IDEA.app/Contents/lib/platform-loader.jar:...
[zk.scripting] Base-dir candidates (startDirs): [] (classLoader=com.intellij.util.lang.UrlClassLoader)
[zk.scripting] Falling back to CWD-relative scripts base dir: assets (CWD=/)
```

followed by the "Imported source file not found" banner. So the CWD-relative base is
guaranteed to fail in the IDE - not by luck, by construction.

### The classloader catch

JetBrains' suggestion ("define assets absolute path") begs the question of *where a plain
library gets an absolute project path at runtime*. There is exactly one viable source: the
classpath the IDE assembles to load the script definitions ("Classpath for loading script
definitions", visible in the IDE log). It contains the project's own compiled output:

```
[/Users/.../.m2/repository/zk/pub/0.1.0/pub-0.1.0.jar,
 /Users/.../minimal_scripting_repro/srcs/pub/build/classes/kotlin/main,
 /Users/.../minimal_scripting_repro/srcs/pub/build/resources/main,
 <kotlin jars...>]
```

Walking up from `build/classes/kotlin/main` reaches the project root, and with it the
`assets/` directory. So: read the classpath of the loader that loaded this library, and
walk up from the directory entries.

The catch: the standard way to read a classpath (`java.net.URLClassLoader.getURLs()`) does
not apply, because IntelliJ loads script definitions with its own
`com.intellij.util.lang.UrlClassLoader`, which **does not extend `java.net.URLClassLoader`**
(verified at runtime from the IDE log; the source is
`platform/util-class-loader/src/com/intellij/util/lang/UrlClassLoader.java` in
`JetBrains/intellij-community`). The first version of the fix therefore fell back to
`java.class.path` - which in the IDE lists only IDE *application* jars (all `.jar`, no
directories) - found no project directories, and fell through to the broken CWD fallback.

The working version invokes a public no-arg `getUrls()` / `getURLs()` method
**reflectively**. `UrlClassLoader` happens to expose `public final List<URL> getUrls()`
(returning plain `file:` URLs for both directories and jars), which is exactly what we
need. Reflection is the only option here: the `pub` library is a plain published artifact
and must not reference IntelliJ platform classes at compile time.

## The fix

`resolveScriptImportsBaseDir()` in
`srcs/pub/src/main/kotlin/zk/pub/scripts/UserScriptCompilationConfiguration.kt` resolves the
directory that `@Import` paths are relative to, as an **absolute** path:

1. **Explicit override**: `-Dzk.assetsDir=<absolute path>` system property or the
   `ZK_ASSETS_DIR` environment variable (must be absolute; validated). The manual version of
   the JetBrains suggestion - use it when no project directories are on the classpath at
   all (e.g. the library is consumed as a jar from a dependency cache).
2. **Discovery**: walk up from candidate on-disk locations, in priority order (stronger
   signals first), to the nearest ancestor containing an `assets` folder:
   - **a.** the directory this class is loaded from, when it comes from a directory
     classpath (Gradle tests, the IDE's compiled-classes output);
   - **b.** every `file:` **directory** entry on the classpath of the loader that loaded
     this class - this is what makes the IDE case work (see above);
   - **c.** (weaker signal, checked last) the parent directories of `jar:` entries: the jar
     this class was loaded from first, then every other jar's parent. Useful when the
     library jar sits inside the project; harmless and fruitless when it sits in
     `~/.m2` (the walk simply finds no `assets` folder before leaving home).
   - Directory entries are tried before jar parents because they are a far stronger signal
     that the classpath belongs to this project.
3. **Fallback**: the legacy CWD-relative `assets/`. Only correct when the process's working
   directory is the project root (e.g. an app launched from its root); kept so existing
   behavior is unchanged for those hosts.

Verified against the real IDE (IntelliJ 2026.2.4, Kotlin 2.4.10). With
`-Dzk.debug.scriptImports=true`, idea.log (as `INFO - STDOUT` lines) now shows:

```
[zk.scripting] This class is loaded from: jar:file:/Users/zachkehs/.m2/repository/zk/pub/0.1.0/pub-0.1.0.jar!/zk/pub/scripts/UserScriptCompilationConfiguration.class
[zk.scripting] Retrieved classpath from com.intellij.util.lang.UrlClassLoader via getUrls()
[zk.scripting] Base-dir candidates: directories=[.../srcs/pub/build/classes/kotlin/main, .../srcs/pub/build/resources/main] jar-parents=[...]
[zk.scripting] Discovered scripts base dir from .../srcs/pub/build/classes/kotlin/main: /Users/zachkehs/development/minimal_scripting_repro/assets
[zk.scripting] Import source resolved to: /Users/zachkehs/development/minimal_scripting_repro/assets/utility_scripts/x.utils.kts (exists=true)
```

`x.example.kts` then analyzes without the "Imported source file not found" error, and the
code-vision inlay reads `Script definition: .example.kts`.

## Why the workaround is unreasonable (the pushback for the ticket)

- The only runtime source of an absolute project path available to a **plain library** is
  the IDE's own definition classpath, and the IDE hides it behind a non-standard
  classloader, so the discovery needs reflection against IDE-internal API. There is no
  documented, supported hook for "where is this project?".
- The straightforward implementation (CWD-relative) is broken by the IDE's choice of CWD
  (`/` on macOS). If the IDE ran script analysis with the project content root as the CWD -
  or resolved relative script paths against the content root - none of this would be
  needed, and the "compiler can, IDE can't" asymmetry in the ticket title would simply
  disappear.
- Exposing the project root to configuration handlers (e.g. on
  `ScriptConfigurationRefinementContext`) or providing a supported helper to read the
  definition classloader would let libraries drop the reflection.
- Aggravating factor: on 2026.2.x this only works at all with the explicit "Kotlin script
  definitions" settings, because auto-discovery is broken -
  [KTIJ-40199](https://youtrack.jetbrains.com/issue/KTIJ-40199), documented in
  [intellij_issue_draft_script_definition_discovery_2026_2.md](intellij_issue_draft_script_definition_discovery_2026_2.md).
  Two separate IDE bugs stacked before the import question even starts.

## Verifying the fix from the command line (no IDE needed)

`./gradlew :srcs:pub:reproIdeScriptAnalysis` reproduces the scenario without the IDE,
from a process working directory that is NOT the project root (just like the IDE
process): the script definition classes come from the module's directory classpath and
the script is compiled in-process, exactly like the IDE does. It is wired into `check`
(runs as part of `./gradlew build`) and fails the build when the `@Import` cannot be
resolved - before the fix it failed with `Imported source file not found:
assets/utility_scripts/x.utils.kts`, the same error the IDE shows. The task sets
`-Dzk.debug.scriptImports=true` so the resolved base directory is printed.

Two further harnesses were built while debugging, mimicking the IDE's jar-first class
loading via the IDE's K2 refinement flow and via the K1 CLI pipeline. Both were removed
again: neither could fail the build when the import did not resolve (they only printed a
verdict and exited 0), and neither covered a discovery branch beyond the task above.

## Diagnostics

`-Dzk.debug.scriptImports=true` on the running JVM (IDE: Help | Edit Custom VM Options)
makes the import handler log, to stdout (captured in idea.log as `INFO - STDOUT` lines) and
to `/tmp/zk-script-debug.log`:

- the base-dir resolution steps (class load location, classloader classpath, candidates,
  discovered base, each resolved import and whether the file exists);
- for the script currently being refined, the compilation configuration the host built for
  the matched definition: `fileExtension`, `displayName`, every stored property, the
  computed code-vision inlay text, and where each definition class was loaded from
  (codeSource) with its runtime `@KotlinScript` annotation values.

That dump is what settles definition-matching and inlay-display questions: it shows the
exact `fileExtension` the "Script definition: ..." inlay displays, and whether it matches
what the annotation in the published jar says.
