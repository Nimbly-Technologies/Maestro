package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.DeviceInfo
import maestro.TreeNode
import maestro.device.Platform
import org.junit.jupiter.api.Test

class JevHealingVisibleCompositeTest {
    @Test
    fun clickableCardRemainsACandidateWhenItsChildCoversItsCenter() {
        val card = TreeNode(
            attributes = mutableMapOf(
                "bounds" to "[0,0][240,100]",
                "accessibilityText" to "SCHEDULES, Digital Audits & Checklists",
                "class" to "android.view.ViewGroup",
            ),
            clickable = true,
            enabled = true,
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf(
                        "bounds" to "[20,20][220,80]",
                        "text" to "SCHEDULES",
                        "class" to "android.widget.TextView",
                    ),
                    enabled = true,
                ),
            ),
        )
        val device = DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = 300,
            heightPixels = 300,
            widthGrid = 300,
            heightGrid = 300,
        )

        val candidates = JevHealingCandidates.collect(TreeNode(children = listOf(card)), device, 32)

        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().element.treeNode).isSameInstanceAs(card)
        assertThat(candidates.single().payload.label).contains("SCHEDULES")
    }
}
