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
