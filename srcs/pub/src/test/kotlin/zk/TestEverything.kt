package zk

import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test
import zk.pub.content.registries.UserScriptRegistry
import zk.pub.scripts.runExampleScript

class TestEverything {
    @Test
    fun testScriptSetup() {
        val registry = UserScriptRegistry()
        val scriptPath = "assets/scripts/x.example.kts"

        // Compile & Prepare to Run:
        registry.loadScript(scriptPath)

        val input = 0
        val out =
            runExampleScript(
                userScripts = registry,
                inputNumber = input,
                pathWithExtension = scriptPath,
            )
        assertNotEquals(input, out)
    }
}
