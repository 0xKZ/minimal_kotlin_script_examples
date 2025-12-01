// If we need to access the kotlin Version anywhere in here this is how we would do it:
// val kotlinVersion:String by settings

rootProject.name = "zk"

// Note: project names should be kebab-case (which is lowercase separated by dashes).
include(
// "Public" gradle project that gets exported for developers of mods to code against / have a usable IDE experience.
"srcs:pub",
)

// According to the tut, if these are missing we must fall bck to teh "buildscirpt block" in the build.gradle root
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
