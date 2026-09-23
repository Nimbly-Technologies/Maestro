package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.DeviceInfo
import maestro.TreeNode
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JevHealingTest {
    private val deviceInfo = DeviceInfo(
        platform = Platform.ANDROID,
        widthPixels = 100,
        heightPixels = 100,
        widthGrid = 100,
        heightGrid = 100,
    )

    @Test
    fun `collects bounded visible candidates and redacts sensitive labels`() {
        val root = TreeNode(
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf(
                        "bounds" to "[0,0][50,40]",
                        "text" to "Sign in",
                        "class" to "android.widget.Button",
                    ),
                    enabled = true,
                ),
                TreeNode(
                    attributes = mutableMapOf(
                        "bounds" to "[0,45][50,80]",
                        "text" to "secret-password",
                        "class" to "android.widget.EditText",
                    ),
                    enabled = true,
                ),
                TreeNode(
                    attributes = mutableMapOf(
                        "bounds" to "[200,0][250,40]",
                        "text" to "Off screen",
                    ),
                    enabled = true,
                ),
            ),
        )

        val candidates = JevHealingCandidates.collect(root, deviceInfo, maxCandidates = 2)

        assertThat(candidates).hasSize(2)
        assertThat(candidates[0].payload.label).isEqualTo("Sign in")
        assertThat(candidates[1].payload.label).isEqualTo("[redacted]")
        assertThat(candidates.map { it.payload.id }).containsExactly("c1", "c2").inOrder()
    }

    @Test
    fun `generic views cannot consume the candidate bound before a meaningful control`() {
        val largeDevice = deviceInfo.copy(
            widthPixels = 1000,
            heightPixels = 1000,
            widthGrid = 1000,
            heightGrid = 1000,
        )
        val genericViews = (0 until 40).map { index ->
            TreeNode(
                attributes = mutableMapOf(
                    "bounds" to "[0,${index * 10}][50,${index * 10 + 5}]",
                    "class" to "android.view.View",
                ),
                clickable = false,
                enabled = true,
            )
        }
        val button = TreeNode(
            attributes = mutableMapOf(
                "bounds" to "[0,500][160,560]",
                "text" to "Continue",
                "class" to "android.widget.Button",
            ),
            clickable = true,
            enabled = true,
        )

        val candidates = JevHealingCandidates.collect(
            TreeNode(children = genericViews + button),
            largeDevice,
            maxCandidates = 32,
        )

        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().payload.label).isEqualTo("Continue")
    }

    @Test
    fun `preserves a clickable parent when its text is supplied by a child`() {
        val largeDevice = deviceInfo.copy(
            widthPixels = 1000,
            heightPixels = 1000,
            widthGrid = 1000,
            heightGrid = 1000,
        )
        val parent = TreeNode(
            attributes = mutableMapOf(
                "bounds" to "[0,0][240,80]",
                "class" to "android.widget.Button",
            ),
            clickable = true,
            enabled = true,
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf(
                        "bounds" to "[20,20][220,60]",
                        "text" to "Continue",
                        "class" to "android.widget.TextView",
                    ),
                    enabled = true,
                ),
            ),
        )

        val candidates = JevHealingCandidates.collect(
            TreeNode(children = listOf(parent)),
            largeDevice,
            maxCandidates = 32,
        )

        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().payload.label).isEqualTo("Continue")
        assertThat(candidates.single().element.treeNode).isEqualTo(parent)
    }

    @Test
    fun `retains a password field label while removing its value`() {
        val root = TreeNode(
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf(
                        "bounds" to "[0,0][200,60]",
                        "class" to "android.widget.EditText",
                        "hintText" to "Password field",
                        "text" to "correct-horse-battery-staple",
                    ),
                    enabled = true,
                ),
            ),
        )

        val candidate = JevHealingCandidates.collect(root, deviceInfo, maxCandidates = 4).single()

        assertThat(candidate.payload.label).isEqualTo("Password field")
        assertThat(candidate.payload.label).doesNotContain("correct-horse-battery-staple")
    }

    @Test
    fun `healing is disabled unless explicitly configured and endpoint stays local`() {
        assertThat(JevHealingSettings.from(MaestroConfig()).enabled).isFalse()

        val config = MaestroConfig(
            ext = mapOf(
                "jevHealing" to mapOf(
                    "enabled" to true,
                    "timeoutMs" to 10_000,
                    "maxCandidates" to 100,
                    "endpoint" to "http://127.0.0.1:8767/v1/mobile/heal",
                ),
            ),
        )
        val settings = JevHealingSettings.from(config)
        assertThat(settings.enabled).isTrue()
        assertThat(settings.timeoutMs).isEqualTo(4_000L)
        assertThat(settings.maxCandidates).isEqualTo(32)

        assertThrows<IllegalArgumentException> {
            JevHealingSettings.from(
                MaestroConfig(
                    ext = mapOf(
                        "jevHealing" to mapOf(
                            "enabled" to true,
                            "endpoint" to "https://example.com/heal",
                        ),
                    ),
                ),
            )
        }
    }
}
