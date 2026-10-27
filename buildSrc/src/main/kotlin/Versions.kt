object Versions {
    const val jvmMajorVersion = 21;
    const val kotlinCoroutinesVersion = "1.8.1"

    // Version of the published script-definitions artifact (coordinates zk:pub). Bump this
    // when the script definitions change if consumers cache the artifact by version.
    const val pubVersion = "0.1.0"

    // Note: JUnit version is DUPLICATED in the common conventions folder (similarly can't do here b/c of circular dep)
    // Always update them in sync...
    const val junit = "5.11.4"
}