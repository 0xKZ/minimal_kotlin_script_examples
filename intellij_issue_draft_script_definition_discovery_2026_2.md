# (Draft) YouTrack issue: custom script definitions are never discovered from a published library JAR on 2026.2.x - the marker scan depends on a `filename` index the IDE never builds

*Draft prepared 2026-10-27 from a real reproduction with full log evidence. Ready to file
against the Kotlin scripting area, as a follow-up to KTIJ-38886 / KTIJ-40199.*

- **Area:** Kotlin / Scripting (K2)
- **IDE:** IntelliJ IDEA 2026.2.3, build IU-262.10968.63 (same failure on 2026.2.1, IU-262.9437.185)
- **Kotlin plugin:** bundled, Kotlin 2.4.10
- **OS:** macOS 27.0.1 (arm64)
- **Repro repo:** this repository (`minimal_scripting_repro`) at the commit "Discover script definitions from a published library (KTIJ-40199)"
- **Related:** [KTIJ-38886](https://youtrack.jetbrains.com/issue/KTIJ-38886) (discovery rework), [KTIJ-40199](https://youtrack.jetbrains.com/issue/KTIJ-40199) (source-root discovery removed; JetBrains: definitions must come from a built artifact on the module classpath)

## Summary

With exactly the setup JetBrains prescribes in KTIJ-40199 - a script-definition JAR with
the `META-INF/kotlin/script/templates/*.classname` touchfiles, published to mavenLocal,
and attached as a **library** of the module that contains the `.kts` files -
IntelliJ 2026.2.x **never discovers the custom `@KotlinScript` definitions**.

This is not a timing problem. The failure was reproduced with the file index fully warm,
the Gradle model fully synced, and the discovery cache explicitly invalidated (both by a
Gradle re-sync, which bumps `ScriptDefinitionsModificationTracker` via
`KotlinDslGradleSyncListener`, and by re-applying the Kotlin Scripting settings). The
re-scans run in fully smart mode and still find nothing.

The cause is in the 2026.2.x implementation itself: the marker scan queries the
**`filename` index** (`FilenameIndex.getVirtualFilesByName("templates", project.allScope())`)
and then keeps only **directory** hits. In the attached log the `filename` index is
**never built in any session** (zero index-scheduling events for it, across two projects
and two 2026.2 builds), so the scan can only ever return an empty list. The alternative
classpath-scanning discovery source is disabled in the same class
(`useDiscovery() = false`).

On `master` (to become 2026.3+) discovery has already been reworked to walk the class
roots directly in the VFS, with no index dependency (see "Fix, already on master").
Request: backport that (at least the index-independent marker lookup) to the 2026.2.x line.

## Reproduction

1. Check out this repo at the mentioned commit.
2. `./gradlew buildScriptDefinitions` - builds `:srcs:pub` and publishes `zk:pub:0.1.0`
   to `~/.m2`. The JAR contains the definition classes
   (`zk/pub/scripts/definitions/UtilityScript.class`, ...) **and** the marker touchfiles
   (`META-INF/kotlin/script/templates/zk.pub.scripts.definitions.UtilityScript.classname`,
   `META-INF/kotlin/script/templates/zk.pub.scripts.definitions.ExampleScript.classname`).
3. Open the project in IntelliJ 2026.2.x, run a Gradle sync. The root module now has the
   library `Gradle: zk:pub:0.1.0` (class root `pub-0.1.0.jar`).
4. Wait for indexing to fully finish (bottom-right progress bar gone; an indexing
   diagnostic dump shows `scheduled: 0` remaining for the project).
5. Open `assets/utility_scripts/x.utils.kts`.

**Expected:** the editor banner shows the custom `UtilityScript` definition; the
top-level property `numberThatTheScriptCanAccess` (declared in the definition) resolves.

**Actual:** the banner shows `script definition: .kts`; `numberThatTheScriptCanAccess`
is unresolved. This persists through:

- additional Gradle syncs (each sync bumps `ScriptDefinitionsModificationTracker` via
  `KotlinDslGradleSyncListener.onSyncPhaseCompleted` -> `KotlinScriptService.scheduleReloadOpenScripts()`,
  so the discovery cache is invalidated and the scan really re-runs),
- bumping the Kotlin Scripting settings (Settings -> Languages & Frameworks -> Kotlin ->
  Kotlin Scripting -> toggle a definition entry -> Apply -> toggle back -> Apply; this
  also bumps the tracker - the re-scan really re-runs),
- closing/reopening the file, restarting the IDE (both cold "Invalidate caches and
  restart" and a plain warm restart).

The only in-IDE workaround that works on 2026.2.3 is the explicit definition list in the
same settings page (see "Workaround" below).

## Evidence (from `idea.log`, session 2026-10-27, warm restart at 15:42)

| Time | Event |
|------|-------|
| 15:42:23,057 | `Indexes are already loaded` - the file index is restored from disk, the project opens warm. |
| 15:42:23,199-223 | Scanner indexes the `pub-0.1.0.jar` contents (`MiscKt.class`, `UserScriptRegistry.class`, `zk.pub.scripts.definitions.ExampleScript.classname`, ...). The `.classname` markers are indexed with appliers `[filetypes]` only. |
| 15:42:23,223 | enter dumb mode (on-open scan). |
| 15:42:23,299 | First `[KOTLIN_SCRIPTING] processing definitions provider ...DefinitionFromDependenciesProvider` (dumb mode) - finds nothing. |
| 15:42:23,915 | On-open scan finished (522 ms total). |
| 15:42:23,928 | exit dumb mode - **fully smart from here on**. |
| 15:42:26,789 | Gradle sync completes (model v11, `The Gradle project sync`). `KotlinDslGradleSyncListener` fires -> `scheduleReloadOpenScripts()` -> `invalidateAll()` + `ScriptDefinitionsModificationTracker.incModificationCount()` -> the dependency-discovery cache is invalidated. |
| 15:42:27,090 / 15:42:28,897-29,133 | `DefinitionFromDependenciesProvider` queried again during model updates - **fresh scan, smart mode, warm index, complete model** - still no `Loaded definition=zk.pub.*`. |
| 15:42:36,204 | Indexing diagnostic: scan `PARTIAL`, 3863 files, **`scheduled: 0`** after the scan (the JAR is fully indexed; the model includes `Library Gradle: zk:pub:0.1.0 (class root pub-0.1.0.jar) -> 29 files`). |
| 15:43:16,900 | Kotlin Scripting settings page opened **in the zk project** (the only project open at the time - the previous project was closed at 15:42:22 and none was opened afterwards). |
| 15:43:28,589 / 15:43:31,698 | Two `processing definitions provider` bursts (uncheck -> Apply -> recheck -> Apply) - two more fresh scans under fully warm conditions - still no `zk.pub.*` definitions. |
| 15:46:52 | Another full Gradle sync + `removing all .kts entities` / `updating kotlin script entities` - another fresh scan - still nothing. |
| (whole log) | The only `Loading script definitions:` lines are for the bundled Gradle DSL definitions. **`zk.pub.scripts.definitions.UtilityScript` / `ExampleScript` are never loaded in any session of this log** (three days, two projects, builds IU-262.9437.185 and IU-262.10968.63). |

**The decisive observation.** The log contains 641 index-scheduling lines
(`Scheduling indexing of ... by request of indexes: [...]`) over those three days. The
union of requested index ids includes `filetypes`, `Stubs`, `fileIncludes`,
`KotlinClassFileIndex`, `KotlinTopLevelClassLikeDeclarationByPackageShortNameIndex`, ...
- i.e. the logger does record the ids of lazily-built indexes when they are requested.
**The `filename` index is never requested or built in any session** (the literal string
`filename` does not occur in the log at all), while the 2026.2.x discovery scan queries
exactly that index. A `FilenameIndex.getVirtualFilesByName(...)` call against a never-
built index does not trigger its build (no scheduling event appears around any of the
re-scan timestamps above); it simply returns an empty result. The marker scan can
therefore never succeed on 2026.2.x, regardless of timing, restarts, or settings bumps.

Controls that rule out the usual suspects:

- The published JAR is correct (verified): it contains the definition classes and the
  `.classname` marker files at the expected path.
- The JAR is a library of the Gradle module (it is indexed as
  `Library Gradle: zk:pub:0.1.0 (class root pub-0.1.0.jar)`; the library is in the
  synced model).
- `./gradlew build` (runtime, `zk:pub` as a real dependency) works - the definitions load
  and the scripts run fine outside the IDE.
- Re-running the discovery under fully warm, smart-mode conditions (sync-triggered and
  settings-triggered re-scans) finds nothing - so this is not a stale-cache artifact.

## Root cause (source of branch `262.10968`)

`plugins/kotlin/base/scripting/scripting.k2/src/org/jetbrains/kotlin/idea/core/script/k2/definitions/DefinitionFromDependenciesProvider.kt`:

```kotlin
internal object ScriptTemplatesFromDependenciesCache {
    fun getOrDiscover(project: Project): DefinitionTemplates = project.cacheByClass(
        ScriptTemplatesFromDependenciesCache::class.java,
        ScriptDefinitionsModificationTracker.getInstance(project),
    ) {
        runReadActionBlocking {
            val templatesFolders =
                FilenameIndex.getVirtualFilesByName(ScriptDefinitionMarkerFileType.lastPathSegment, project.allScope())
            val files = templatesFolders.asSequence()
                .filter { ScriptDefinitionMarkerFileType.isParentOfMyFileType(it) }  // requires isDirectory
                .filter { projectFileIndex.isInLibraryClasses(it) }
                ...
        }
    }
}
```

Two structural problems combine:

1. **The marker lookup depends on the lazily-built `filename` index - which nothing in
   the normal IDE flow builds.** Markers are located via
   `FilenameIndex.getVirtualFilesByName("templates", project.allScope())`, and the filter
   (`ScriptDefinitionMarkerFileType.isParentOfMyFileType`) requires the hit to be a
   *directory* named `META-INF/kotlin/script/templates`. The attached log shows the
   `filename` index was never built in any session (see "The decisive observation"), so
   this lookup always returns empty. (Even if the index were built, it is not evident
   that jar *directory* entries are part of it - in the log the jar's `.classname`
   *files* are indexed only for `filetypes`.) The classpath-scanning alternative that
   would not need any index is explicitly disabled in the same class:
   `override fun useDiscovery(): Boolean = false`.
2. **Secondary (still real): a first-access-wins session cache.** The `CachedValue`
   (via `Project.cacheByClass`) is keyed only on `ScriptDefinitionsModificationTracker`.
   The first lookup - triggered by the auto-restored `.kts` editor ~2 seconds after
   startup, while the project is still in dumb mode - caches an empty result, and
   invalidation only comes from the settings UI, the Gradle sync listener, or the
   script-reload actions - not from index completion by itself. On 262.10968 this is
   mostly masked by problem 1 (the re-scans fail anyway), but it would still bite any
   implementation whose scan actually depends on a warm index.

## Fix (already on `master`)

`plugins/kotlin/base/scripting/scripting/src/org/jetbrains/kotlin/idea/core/script/definitions/DefinitionFromDependenciesProvider.kt`
(note the moved directory, `scripting` instead of `scripting.k2`) on `master`
(fetched 2026-10-27) addresses all of the above:

1. **No index dependency for marker discovery.** Candidates are walked directly in the
   VFS: `classRoot.findFileByRelativePath(SCRIPT_DEFINITION_MARKERS_PATH)` for every
   class root, instead of `FilenameIndex.getVirtualFilesByName`.
2. **Module outputs are candidates again.** `markerCandidateRoots(project)` = the
   production output roots of all modules plus the class roots of all libraries (no
   `ProjectFileIndex.isInLibraryClasses` filter), which also resolves the
   "sibling module's `build/classes` are invisible" part of KTIJ-40199 for 2026.3+.
3. **Cache invalidation on model changes.** `getOrDiscover` is keyed on *both*
   `ScriptDefinitionsModificationTracker` **and** `ProjectRootModificationTracker`, so a
   Gradle sync (which changes the project roots/model) invalidates the result.
4. **Dumb-mode awareness.** `tryToGuessClasspath` checks `DumbService.isDumb` and, if
   the lookup ran while dumb, schedules
   `ScriptDefinitionsModificationTracker.incModificationCount()` via
   `dumbService.runWhenSmart { ... }`; and a `ScriptDefinitionsBuildListener` (a
   `ProjectTaskListener` in the same package) re-scans after successful builds, bumping
   the tracker when the marker files changed.

Request: backport item 1 (the index-independent marker lookup) - ideally with items 3 and
4 - to the 2026.2.x line.

## Workaround (verified against the `262.10968` sources)

The explicit definition list in the Kotlin Scripting settings page works on 2026.2.3,
because it does not touch the broken scan:

1. Settings -> Build, Execution, Deployment -> Kotlin -> **Kotlin Scripting**.
2. In **"Script definition classes to load:"** enter the definition class FQNs
   (comma- or newline-separated), e.g.
   `zk.pub.scripts.definitions.UtilityScript, zk.pub.scripts.definitions.ExampleScript`.
3. In **"Classpath for loading script definitions:"** enter the absolute path of the
   published JAR (e.g. `/Users/<you>/.m2/repository/zk/pub/0.1.0/pub-0.1.0.jar`).
   (`~` is not expanded - use an absolute path.)
4. Apply.

Source-level justification (`BridgeScriptDefinitionsContributor` +
`scriptDefinitionLoading.kt` on `262.10968`): the explicit classes are loaded with a
`UrlClassLoader` over exactly that classpath (parent: the Kotlin plugin loader, which
provides the scripting runtime), and `getDefinitionsClassPath()` additionally falls back
to resolving the explicit FQNs via `JavaPsiFacade.findClass` and using the owning
module's class roots (`tryToGuessClasspath`) - so step 3 can also be omitted if the FQNs
resolve via Go-to-Class. Loaded definitions are enabled by default
(`isScriptDefinitionEnabled` defaults to `true`). The values persist per project in
`workspace.xml`, so this survives restarts but is not shareable via the repo - each
machine needs the two minutes of settings.

Note: the settings *toggle* workaround (uncheck/recheck) does **not** fix 2026.2.x
discovery - it only re-runs the broken scan.
