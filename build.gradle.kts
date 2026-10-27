import Versions.pubVersion

/**
 * This build file exists for one reason: since the Kotlin scripting discovery rework
 * (KTIJ-38886; the regression was tracked in KTIJ-40199), IntelliJ no longer discovers custom
 * @KotlinScript script definitions from .kt source roots.
 *
 * In 2026.2.x the IDE discovers them by scanning LIBRARIES on the classpath of the module that
 * contains the .kts files (it looks for .classname marker files under the
 * META-INF/kotlin/script/templates directory inside library class roots). Project MODULE output directories are explicitly NOT
 * scanned in 2026.2.x - the discovery code filters candidates with
 * ProjectFileIndex.isInLibraryClasses() and only accepts LibraryOrSdkOrderEntry order entries
 * (this was fixed on master after 2026.2, so 2026.3+ will also scan module outputs).
 *
 * Our user scripts live in assets/, which is part of this (root) project. So the script
 * definitions must reach THIS module's classpath as a library artifact: :srcs:pub publishes
 * zk:pub:VERSION to mavenLocal, and this project depends on those coordinates. Gradle sync
 * then adds the published jar (and its POM-declared transitive dependencies) as a library.
 *
 * IMPORTANT (2026.2.x): even with all of this in place, the IDE's dependency scan is BROKEN
 * on 2026.2.x - it looks for the markers via the platform `filename` index
 * (FilenameIndex.getVirtualFilesByName("templates", ...)), which the IDE never builds in
 * practice, so the scan always comes up empty (verified with idea.log under fully warm
 * conditions; a settings toggle does NOT fix it). On 2026.2.x the definitions therefore
 * also have to be listed EXPLICITLY (once, per machine): Settings -> Build, Execution,
 * Deployment -> Kotlin -> Kotlin Scripting -> "Script definition classes to load:" = the
 * definition FQNs (zk.pub.scripts.definitions.UtilityScript, ...), "Classpath for loading
 * script definitions:" = the absolute path of the mavenLocal jar, Apply. The values persist
 * in workspace.xml and survive restarts. On master/2026.3+ the scan walks the class roots
 * directly (no FilenameIndex) and plain build + sync works.
 *
 * Workflow (fresh checkout, or after adding/changing script definitions):
 *   1. ./gradlew buildScriptDefinitions     (also run by plain ./gradlew build)
 *   2. Re-sync Gradle in the IDE.
 *   3. 2026.2.x only: fill in the explicit Kotlin Scripting settings (see above).
 *
 * See README.md ("Making the custom script definitions work in the IDE") and
 * intellij_issue_draft_script_definition_discovery_2026_2.md for details and evidence.
 *
 * Note: we intentionally do NOT apply the zk.kotlin-*-conventions plugins here. This module has
 * no sources of its own; it only exists to carry the script classpath, so we keep it minimal
 * (no spotless/serialization/test wiring).
 */
plugins {
    // The version comes from the kotlin-gradle-plugin that buildSrc puts on the build classpath.
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    // mavenLocal first: zk:pub is published there by :srcs:pub:publishToMavenLocal.
    mavenLocal()
    mavenCentral()
}

dependencies {
    // The compiled script definitions (zk.pub.scripts.definitions.*) and their
    // META-INF/kotlin/script/templates touchfiles. Consumed as a published LIBRARY (not a
    // project dependency) because 2026.2.x only scans libraries for script definitions.
    implementation("zk:pub:$pubVersion")
}

tasks.register("buildScriptDefinitions") {
    group = "scripting"
    description =
        "Builds and publishes the script-definition library (zk:pub) to mavenLocal, for the " +
            ".kts files under assets/. Run this after a fresh checkout or after adding new " +
            "script definitions, then re-sync (2026.2.x only: also add the definition FQNs " +
            "to the explicit Kotlin Scripting settings - the IDE's own discovery scan is " +
            "broken there). See README.md."
    dependsOn(":srcs:pub:publishToMavenLocal")
}

// Make the standard build cover the scripting artifact too, so `./gradlew build` is enough.
tasks.named("build") {
    dependsOn("buildScriptDefinitions")
}
