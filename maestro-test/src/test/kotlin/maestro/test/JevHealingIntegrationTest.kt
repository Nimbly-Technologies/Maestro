package maestro.test

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.device.Platform
import maestro.orchestra.JevHealingClient
import maestro.orchestra.JevHealingDecision
import maestro.orchestra.JevHealingRequest
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeDriver.Event
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import org.junit.jupiter.api.Test
import java.nio.file.Files

class JevHealingIntegrationTest {

    @Test
    fun `yaml opt in heals an actual failed tap through the normal driver`() {
        val driver = androidDriver()
        val requests = mutableListOf<JevHealingRequest>()

        Maestro(driver).use { maestro ->
            val result = runBlocking {
                Orchestra(
                    maestro = maestro,
                    lookupTimeoutMs = 0L,
                    optionalLookupTimeoutMs = 0L,
                    jevHealingClient = JevHealingClient { request ->
                        requests += request
                        JevHealingDecision(
                            candidateId = request.candidates.single().id,
                            confidence = 0.98,
                            operationConfidence = 0.99,
                            probability = 0.98,
                            margin = 0.80,
                        )
                    },
                ).runFlow(readHealingFlow())
            }

            assertThat(result.success).isTrue()
            assertThat(result.debugOutput.executedSteps.any { it.jevHealing?.status == "healed" }).isTrue()
        }

        assertThat(requests).hasSize(1)
        assertThat(requests.single().selector).contains("Missing")
        assertThat(requests.single().candidates.single().label).isEqualTo("Continue")
        driver.assertAnyEvent { it is Event.Tap }
    }

    @Test
    fun `yaml opt in preserves the original lookup error when healing is refused`() {
        val driver = androidDriver()
        val requests = mutableListOf<JevHealingRequest>()
        var originalError: Throwable? = null

        Maestro(driver).use { maestro ->
            val result = runBlocking {
                Orchestra(
                    maestro = maestro,
                    lookupTimeoutMs = 0L,
                    optionalLookupTimeoutMs = 0L,
                    jevHealingClient = JevHealingClient { request ->
                        requests += request
                        JevHealingDecision(
                            candidateId = request.candidates.single().id,
                            confidence = 0.98,
                            operationConfidence = 0.40,
                            probability = 0.98,
                            margin = 0.80,
                        )
                    },
                    onCommandFailed = { _, _, error ->
                        originalError = error
                        Orchestra.ErrorResolution.FAIL
                    },
                ).runFlow(readHealingFlow())
            }

            assertThat(result.success).isFalse()
        }

        assertThat(requests).hasSize(1)
        assertThat(originalError).isInstanceOf(MaestroException.ElementNotFound::class.java)
        driver.assertAllEvent { it !is Event.Tap }
    }

    private fun readHealingFlow(): List<maestro.orchestra.MaestroCommand> {
        val path = Files.createTempFile("maestro-jev-healing-", ".yaml")
        Files.writeString(
            path,
            """
            appId: com.example.app
            jevHealing:
              enabled: true
              endpoint: http://127.0.0.1:8767/v1/mobile/heal
              timeoutMs: 4000
              maxCandidates: 32
              minConfidence: 0.70
              minMargin: 0.15
            ---
            - tapOn: Missing
            """.trimIndent(),
        )
        return try {
            YamlCommandReader.readCommands(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun androidDriver(): FakeDriver {
        return FakeDriver(Platform.ANDROID).apply {
            setLayout(
                FakeLayoutElement().apply {
                    element {
                        text = "Continue"
                        bounds = Bounds(20, 20, 180, 80)
                        clickable = true
                    }
                },
            )
            open()
        }
    }
}
