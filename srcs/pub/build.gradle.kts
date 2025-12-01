import Versions.kotlinCoroutinesVersion

val kotlinVersion = project.properties["kotlinVersion"]

plugins { id("zk.kotlin-library-conventions") }

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
