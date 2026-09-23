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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    @Test
    fun `configured lookup timeout lets a late selector win before fallback`() {
        val driver = FakeDriver(Platform.ANDROID)
        val initial = FakeLayoutElement().apply {
            element {
                text = "Continue"
                bounds = Bounds(20, 20, 180, 80)
                clickable = true
            }
        }
        val lateOriginal = FakeLayoutElement().apply {
            element {
                text = "Missing"
                bounds = Bounds(200, 100, 300, 160)
                clickable = true
            }
        }
        val firstHierarchyAt = AtomicLong()
        val providerCalled = AtomicBoolean(false)
        driver.setLayout(initial)
        driver.contentDescriptorOverride = {
            val now = System.nanoTime()
            val first = firstHierarchyAt.updateAndGet { it.takeIf { it != 0L } ?: now }
            if (now - first >= 200_000_000L) lateOriginal.toTreeNode() else initial.toTreeNode()
        }
        driver.open()

        Maestro(driver).use { maestro ->
            val result = runBlocking {
                Orchestra(
                    maestro = maestro,
                    // The flow's 400ms bound must win over this shorter default.
                    lookupTimeoutMs = 50L,
                    optionalLookupTimeoutMs = 0L,
                    jevHealingClient = JevHealingClient { request ->
                        providerCalled.set(true)
                        JevHealingDecision(
                            candidateId = request.candidates.single().id,
                            confidence = 0.98,
                            operationConfidence = 0.99,
                            probability = 0.98,
                            margin = 0.80,
                        )
                    },
                ).runFlow(readHealingFlow(lookupTimeoutMs = 400))
            }

            assertThat(result.success).isTrue()
            assertThat(providerCalled.get()).isFalse()
            assertThat(result.debugOutput.executedSteps.any { it.jevHealing != null }).isFalse()
        }

        driver.assertAnyEvent {
            it is Event.Tap && it.point.x == 250 && it.point.y == 130
        }
    }

    @Test
    fun `late original match wins over the provider candidate`() {
        val driver = FakeDriver(Platform.ANDROID)
        val initial = FakeLayoutElement().apply {
            element {
                text = "Continue"
                bounds = Bounds(20, 20, 180, 80)
                clickable = true
            }
        }
        val lateOriginal = FakeLayoutElement().apply {
            element {
                text = "Missing"
                bounds = Bounds(200, 100, 300, 160)
                clickable = true
            }
        }
        val providerCalled = AtomicBoolean(false)
        driver.setLayout(initial)
        driver.contentDescriptorOverride = {
            if (providerCalled.get()) lateOriginal.toTreeNode() else initial.toTreeNode()
        }
        driver.open()

        Maestro(driver).use { maestro ->
            val result = runBlocking {
                Orchestra(
                    maestro = maestro,
                    lookupTimeoutMs = 0L,
                    optionalLookupTimeoutMs = 0L,
                    jevHealingClient = JevHealingClient { request ->
                        providerCalled.set(true)
                        JevHealingDecision(
                            candidateId = request.candidates.single().id,
                            confidence = 0.98,
                            operationConfidence = 0.99,
                            probability = 0.98,
                            margin = 0.80,
                        )
                    },
                ).runFlow(readHealingFlow(lookupTimeoutMs = 100))
            }

            assertThat(result.success).isTrue()
            val healing = result.debugOutput.executedSteps
                .mapNotNull { it.jevHealing }
                .single()
            assertThat(healing.status).isEqualTo("skipped")
            assertThat(healing.reason).isEqualTo("original_appeared")
        }

        driver.assertAnyEvent {
            it is Event.Tap && it.point.x == 250 && it.point.y == 130
        }
    }

    @Test
    fun `stale provider candidate preserves the original lookup failure`() {
        val driver = FakeDriver(Platform.ANDROID)
        val initial = FakeLayoutElement().apply {
            element {
                text = "Continue"
                id = "continue"
                bounds = Bounds(20, 20, 180, 80)
                clickable = true
            }
        }
        val moved = FakeLayoutElement().apply {
            element {
                text = "Continue"
                id = "continue"
                bounds = Bounds(200, 100, 360, 160)
                clickable = true
            }
        }
        val providerCalled = AtomicBoolean(false)
        var originalError: Throwable? = null
        driver.setLayout(initial)
        driver.contentDescriptorOverride = {
            if (providerCalled.get()) moved.toTreeNode() else initial.toTreeNode()
        }
        driver.open()

        Maestro(driver).use { maestro ->
            val result = runBlocking {
                Orchestra(
                    maestro = maestro,
                    lookupTimeoutMs = 0L,
                    optionalLookupTimeoutMs = 0L,
                    jevHealingClient = JevHealingClient { request ->
                        providerCalled.set(true)
                        JevHealingDecision(
                            candidateId = request.candidates.single().id,
                            confidence = 0.98,
                            operationConfidence = 0.99,
                            probability = 0.98,
                            margin = 0.80,
                        )
                    },
                    onCommandFailed = { _, _, error ->
                        originalError = error
                        Orchestra.ErrorResolution.FAIL
                    },
                ).runFlow(readHealingFlow(lookupTimeoutMs = 100))
            }

            assertThat(result.success).isFalse()
            val healing = result.debugOutput.executedSteps
                .mapNotNull { it.jevHealing }
                .single()
            assertThat(healing.status).isEqualTo("refused")
            assertThat(healing.reason).isEqualTo("stale_candidate")
        }

        assertThat(originalError).isInstanceOf(MaestroException.ElementNotFound::class.java)
        driver.assertAllEvent { it !is Event.Tap }
    }

    @Test
    fun `provider unavailability still prefers a late original match`() {
        val driver = FakeDriver(Platform.ANDROID)
        val initial = FakeLayoutElement().apply {
            element {
                text = "Continue"
                bounds = Bounds(20, 20, 180, 80)
                clickable = true
            }
        }
        val lateOriginal = FakeLayoutElement().apply {
            element {
                text = "Missing"
                bounds = Bounds(200, 100, 300, 160)
                clickable = true
            }
        }
        val providerCalled = AtomicBoolean(false)
        driver.setLayout(initial)
        driver.contentDescriptorOverride = {
            if (providerCalled.get()) lateOriginal.toTreeNode() else initial.toTreeNode()
        }
        driver.open()

        Maestro(driver).use { maestro ->
            val result = runBlocking {
                Orchestra(
                    maestro = maestro,
                    lookupTimeoutMs = 0L,
                    optionalLookupTimeoutMs = 0L,
                    jevHealingClient = JevHealingClient {
                        providerCalled.set(true)
                        throw IllegalStateException("adapter offline")
                    },
                    onCommandFailed = { _, _, _ ->
                        Orchestra.ErrorResolution.FAIL
                    },
                ).runFlow(readHealingFlow(lookupTimeoutMs = 100))
            }

            assertThat(result.success).isTrue()
            assertThat(providerCalled.get()).isTrue()
            val healing = result.debugOutput.executedSteps
                .mapNotNull { it.jevHealing }
                .single()
            assertThat(healing.status).isEqualTo("skipped")
            assertThat(healing.reason).isEqualTo("original_appeared")
        }

        driver.assertAnyEvent {
            it is Event.Tap && it.point.x == 250 && it.point.y == 130
        }
    }

    private fun readHealingFlow(lookupTimeoutMs: Long? = null): List<maestro.orchestra.MaestroCommand> {
        val path = Files.createTempFile("maestro-jev-healing-", ".yaml")
        val lookupTimeout = lookupTimeoutMs?.let { "              lookupTimeoutMs: $it\n" } ?: ""
        Files.writeString(
            path,
            """
            appId: com.example.app
            jevHealing:
              enabled: true
              endpoint: http://127.0.0.1:8767/v1/mobile/heal
              timeoutMs: 4000
$lookupTimeout              maxCandidates: 32
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
