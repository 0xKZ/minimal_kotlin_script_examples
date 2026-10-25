import Versions.kotlinCoroutinesVersion
import Versions.pubVersion

val kotlinVersion = project.properties["kotlinVersion"]

plugins {
    id("zk.kotlin-library-conventions")
    id("maven-publish")
}

// Published coordinates: zk:pub:<pubVersion>. The IDE's Kotlin script definition discovery
// (2026.2.x) only scans LIBRARIES on the classpath for META-INF/kotlin/script/templates
// markers, so consumers depend on these coordinates (see the root build.gradle.kts) instead
// of project(":srcs:pub"). ./gradlew buildScriptDefinitions publishes this to mavenLocal.
group = "zk"

version = pubVersion

// The kotlin-common convention adds `implementation(platform(kotlin-bom))` without a version
// (the version comes from buildSrc's classpath). The published POM/metadata would then contain
// a versionless BOM entry that Gradle consumers cannot parse ("Required version must not be
// null"). All transitive versions are declared explicitly below, so the BOM is not needed by
// consumers - remove it from the outgoing (published) variants.
afterEvaluate {
    configurations.named("implementation") {
        dependencies.removeAll { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-bom" }
    }
}

publishing {
    publications {
        // jar + POM; the POM carries the transitive dependencies so the IDE can load the
        // script definitions with a complete classpath.
        create<MavenPublication>("pub") { from(components["java"]) }
    }
}

dependencies {
    // Kotlin scripting stuff - need the "embeddable" host else pulls in conflicting runtime deps
    // somehow
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion") // Also needed for scripts

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")

    // We publish some test utilities in pub.test - these depend on kotlin test
    implementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
}

// Reproduces KTIJ-36841 without the IDE:
// IntelliJ analyzes scripts by running the @KotlinScript compilation configuration in the IDE
// process, whose working directory is NOT the project root. This task runs the same in-process
// compile (zk.IdeCwdReproMain) with workingDir set outside the repo. With the legacy
// CWD-relative base dir this fails exactly like the IDE; with the jar-aware discovery fix the
// base dir is found by walking up from the module's class directories, so the import resolves.
tasks.register<JavaExec>("reproIdeScriptAnalysis") {
    group = "verification"
    description =
        "Compiles assets/scripts/x.example.kts in-process from a non-project CWD, mimicking " +
            "IntelliJ's script analysis (KTIJ-36841)."

    dependsOn(tasks.named("compileTestKotlin"))

    // The source-set runtime classpath includes this module's own compiled classes
    // (a bare configuration does not):
    classpath = sourceSets.named("test").get().runtimeClasspath
    mainClass.set("zk.IdeCwdReproMainKt")

    // A directory that is deliberately NOT the project root (and NOT inside the repo):
    val fakeIdeCwd = layout.buildDirectory.dir("fake-ide-cwd")
    workingDir(fakeIdeCwd)
    doFirst { fakeIdeCwd.get().asFile.mkdirs() }

    args(File(rootProject.projectDir, "assets/scripts/x.example.kts").absolutePath)

    // Make the import handler print how it resolved the base directory for @Import paths.
    systemProperty("zk.debug.scriptImports", "true")
}

// Run the repro as part of the standard verification, so `./gradlew build` enforces it:
// it fails the build when the @Import cannot be resolved from a non-project CWD.
tasks.named("check") { dependsOn(tasks.named("reproIdeScriptAnalysis")) }
