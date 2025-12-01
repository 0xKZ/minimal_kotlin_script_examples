
plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm")

    // KotlinX Serialization relies on a compiler plugin instead of reflection:
    id("org.jetbrains.kotlin.plugin.serialization")

    // Formatter - version is declared in the buildSrc build.gradle.kts
    id("com.diffplug.spotless")
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}


dependencies {
    // Align versions of all Kotlin components ("bom" means "Bill Of Materials")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    testImplementation("org.junit.jupiter:junit-jupiter:${Versions.junit}")
}

// TODO: How do I get spotless to apply to my build scripts without duplicating the definition?
spotless {
    format("misc") {
        // define the files to apply `misc` to
        target ("**/.gitignore", "**/*.md", "**/*.sh")
        trimTrailingWhitespace()
        indentWithSpaces(4)
        endWithNewline()
    }
    java {
        // apply a specific flavor of google-java-format
        // (I normally take whatever version spotless depends on here-ish
        //   https://github.com/diffplug/spotless/blob/main/lib/build.gradle#L96 )
        googleJavaFormat("1.24.0").aosp()
    }

    // To update this:
    //  - First, update the spotless version in the buildSrc build.gradle.kts.
    //  - Next, look up what the current version of ktfmt that's known to spotless is:
    //         (note that the line may have shifted in the file over time!!)
    //         https://github.com/diffplug/spotless/blob/main/lib/build.gradle#L104
    //  - Then, put that version here:
    val ktfmtVersion = "0.53"
    kotlin {
        // by default the target is every '.kt' and '.kts` file in the java sourcesets.

        // 'kotlinlang' just enforces 4-line indent. It will become the default in ktfmt 1.0, at
        // that time we won't need to specify it. Until then it's using the Facebook/Meta style
        // which is 2-line indent and I don't like that.
        ktfmt(ktfmtVersion).kotlinlangStyle()

        // TODO the below tries to target script files outside of the project (assets folder)
        //      which fails because spotless doesn't allow it. I guess I may have to make a custom
        //      task to target those things? https://github.com/diffplug/spotless/issues/1033
//        target(project.fileTree(project.rootDir) {
//            includes.add("**/*.kt")
//            includes.add("**/*.kts")
//        })

        // Don't bother checking the formatting of generated files
        targetExclude("build/**/*.*")
    }
    kotlinGradle {
        // (see comments in above section)
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
}

tasks.test {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()

    // IntelliJ will default to using the root project directory as working directory for tests,
    // but gradle will default to using the module directory. We want everything to use the same
    // directory so that the assets can be accessed easily, so we standardize on the root directory.
    workingDir = rootDir

    // -xmx on the gradle process is only for the gradle runner that is running the workers.
    // The workers are what are running individual tests, and they default to 512mb.
    maxHeapSize = "2g"

    val args = BuildConstants.TEST_JVM_ARGS.toMutableList()
    if (org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem().isMacOsX) {
        args += BuildConstants.MACOS_JVM_ARGS
    }

    jvmArgs(args)
}

// JVM VERSION / JAVA VERSION:
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(Versions.jvmMajorVersion))
    }
}
