/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package maestro.orchestra

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.DeviceInfo
import maestro.UiElement
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.TreeNode
import maestro.ViewHierarchy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * Metadata for one opt-in mobile healing attempt. Values are deliberately
 * limited to fixed reason codes and redacted selector text.
 */
data class JevHealingMetadata(
    val status: String,
    val originalSelector: String,
    val candidateCount: Int,
    val candidateIds: List<String> = emptyList(),
    val candidateId: String? = null,
    val confidence: Double? = null,
    val operationConfidence: Double? = null,
    val probability: Double? = null,
    val margin: Double? = null,
    val latencyMs: Long? = null,
    val reason: String? = null,
)

data class JevHealingSettings(
    val enabled: Boolean = false,
    val endpoint: URI = DEFAULT_ENDPOINT,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * Optional timeout for the normal element lookup before Jev is consulted.
     * A null value preserves Maestro's existing lookup timeout.
     */
    val lookupTimeoutMs: Long? = null,
    val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    val minMargin: Double = DEFAULT_MIN_MARGIN,
) {
    companion object {
        const val CONFIG_KEY = "jevHealing"
        const val DEFAULT_TIMEOUT_MS = 4_000L
        const val MIN_LOOKUP_TIMEOUT_MS = 100L
        const val MAX_LOOKUP_TIMEOUT_MS = 17_000L
        const val DEFAULT_MAX_CANDIDATES = 32
        const val DEFAULT_MIN_CONFIDENCE = 0.70
        const val DEFAULT_MIN_MARGIN = 0.15
        val DEFAULT_ENDPOINT: URI = URI.create("http://127.0.0.1:8767/v1/mobile/heal")

        fun from(config: MaestroConfig?): JevHealingSettings {
            val raw = config?.ext?.get(CONFIG_KEY)
            if (raw !is Map<*, *>) return JevHealingSettings()
            if (raw["enabled"] != true) return JevHealingSettings()

            val endpoint = URI.create(raw["endpoint"]?.toString() ?: DEFAULT_ENDPOINT.toString())
            require(endpoint.scheme == "http" && endpoint.host?.trim('[', ']') in setOf("127.0.0.1", "localhost", "::1")) {
                "Jev mobile healing endpoint must be a loopback HTTP URL"
            }
            val timeoutMs = raw["timeoutMs"].asLong(DEFAULT_TIMEOUT_MS).coerceIn(100L, DEFAULT_TIMEOUT_MS)
            val lookupTimeoutMs = raw["lookupTimeoutMs"]
                .asLongOrNull()
                ?.coerceIn(MIN_LOOKUP_TIMEOUT_MS, MAX_LOOKUP_TIMEOUT_MS)
            val maxCandidates = raw["maxCandidates"].asInt(DEFAULT_MAX_CANDIDATES).coerceIn(1, DEFAULT_MAX_CANDIDATES)
            val minConfidence = raw["minConfidence"].asDouble(DEFAULT_MIN_CONFIDENCE).coerceIn(0.0, 1.0)
            val minMargin = raw["minMargin"].asDouble(DEFAULT_MIN_MARGIN).coerceIn(0.0, 1.0)
            return JevHealingSettings(
                enabled = true,
                endpoint = endpoint,
                timeoutMs = timeoutMs,
                lookupTimeoutMs = lookupTimeoutMs,
                maxCandidates = maxCandidates,
                minConfidence = minConfidence,
                minMargin = minMargin,
            )
        }

        private fun Any?.asLong(default: Long): Long = when (this) {
            is Number -> toLong()
            is String -> toLongOrNull() ?: default
            else -> default
        }

        private fun Any?.asLongOrNull(): Long? = when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> null
        }

        private fun Any?.asInt(default: Int): Int = when (this) {
            is Number -> toInt()
            is String -> toIntOrNull() ?: default
            else -> default
        }

        private fun Any?.asDouble(default: Double): Double = when (this) {
            is Number -> toDouble()
            is String -> toDoubleOrNull() ?: default
            else -> default
        }
    }
}

data class JevCandidatePayload(
    val id: String,
    val label: String,
    val role: String,
    val resourceId: String? = null,
    val bounds: String,
    val enabled: Boolean,
    val visible: Boolean,
)

data class JevCandidateTarget(
    val payload: JevCandidatePayload,
    val element: UiElement,
)

data class JevHealingRequest(
    val selector: String,
    val candidates: List<JevCandidatePayload>,
)

data class JevHealingDecision(
    val candidateId: String,
    val confidence: Double,
    val operationConfidence: Double,
    val probability: Double,
    val margin: Double,
    val model: String? = null,
    val latencyMs: Long? = null,
)

fun interface JevHealingClient {
    fun select(request: JevHealingRequest): JevHealingDecision
}

class HttpJevHealingClient(
    private val settings: JevHealingSettings,
) : JevHealingClient {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(settings.timeoutMs))
        .build()
    private val mapper = jacksonObjectMapper()

    override fun select(request: JevHealingRequest): JevHealingDecision {
        val body = mapper.writeValueAsString(request)
        val response = client.send(
            HttpRequest.newBuilder(settings.endpoint)
                .timeout(Duration.ofMillis(settings.timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Jev adapter returned HTTP " + response.statusCode())
        }
        val root = mapper.readTree(response.body())
        require(root["ok"]?.asBoolean() == true) { "Jev adapter refused the candidate" }
        val candidateId = root["candidateId"]?.asText()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Jev adapter returned no candidate")
        val confidence = root["confidence"]?.asDouble()
            ?: throw IllegalStateException("Jev adapter returned no confidence")
        val operationConfidence = root["operationConfidence"]?.asDouble()
            ?: root["operation_confidence"]?.asDouble()
            ?: throw IllegalStateException("Jev adapter returned no operation confidence")
        val probability = root["probability"]?.asDouble()
            ?: throw IllegalStateException("Jev adapter returned no probability")
        val margin = root["margin"]?.asDouble()
            ?: throw IllegalStateException("Jev adapter returned no margin")
        require(
            confidence.isFinite() &&
                operationConfidence.isFinite() &&
                probability.isFinite() &&
                margin.isFinite(),
        ) {
            "Jev adapter returned non-finite confidence"
        }
        return JevHealingDecision(
            candidateId = candidateId,
            confidence = confidence,
            operationConfidence = operationConfidence,
            probability = probability,
            margin = margin,
            model = root["model"]?.asText(),
            latencyMs = root["latency_ms"]?.asLong(),
        )
    }
}

object JevHealingCandidates {
    private val EMAIL = Regex("\\b[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b")
    private val BEARER = Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val SENSITIVE = Regex("(?i)(password|passwd|secret|token|api[_ -]?key|authorization)")
    private val SECRET_ASSIGNMENT = Regex(
        "(?i)\\b(?:password|passwd|secret|token|api[_ -]?key|authorization)\\b\\s*[:=]\\s*[^\\s,;]+",
    )
    private val LONG_SECRET = Regex("\\b[A-Za-z0-9_-]{32,}\\b")
    private val SECRET_VALUE = Regex(
        "(?i)^(?:password|passwd|secret|token|api[_ -]?key|authorization)[A-Za-z0-9_.:/=-]{2,}$",
    )
    private val TEXT_INPUT_ROLE = Regex("(?i)(edittext|textfield|textbox|input)")
    private val SAFE_SECRET_LABEL = Regex(
        "(?i)^(?:(?:enter|type|input|confirm|current|new|your|forgot|show|hide)\\s+)?(?:password|passcode|secret|token|api(?:\\s+key)?|authorization)(?:\\s+(?:field|input|button|here))?$",
    )
    private val SEMANTIC_ATTRIBUTES = listOf(
        "accessibilityText",
        "content-desc",
        "contentDescription",
        "hintText",
    )

    private data class RawLabel(
        val value: String,
        val semantic: Boolean,
    )

    fun collect(root: TreeNode, deviceInfo: DeviceInfo, maxCandidates: Int): List<JevCandidateTarget> {
        if (maxCandidates <= 0) return emptyList()

        val candidates = mutableListOf<JevCandidateTarget>()
        val hierarchy = ViewHierarchy(root)

        fun descendantLabel(node: TreeNode): RawLabel? {
            node.children.forEach { child ->
                nodeLabel(child)?.let { return it }
                descendantLabel(child)?.let { return it }
            }
            return null
        }

        fun visit(node: TreeNode, claimedByClickableParent: Boolean) {
            if (candidates.size >= maxCandidates) return

            val element = node.toUiElementOrNull()
            val attrs = node.attributes
            val role = attrs["class"]?.substringAfterLast('.') ?: "android.view.View"
            val ownLabel = nodeLabel(node)
            val inheritedLabel = if (node.clickable == true && ownLabel == null) descendantLabel(node) else null
            val resourceId = attrs["resource-id"]
            val actionableRole = isActionableRole(role)
            val rawLabel = ownLabel ?: inheritedLabel ?: resourceId
                ?.takeIf { node.clickable == true || actionableRole }
                ?.let { RawLabel(it, semantic = false) }
            val inheritedFromChild = inheritedLabel != null
            val clickableParentClaimsChildren =
                node.clickable == true && (ownLabel != null || inheritedFromChild)
            val visibleGeometry = element?.let {
                val bounds = it.bounds
                val visibleWidth = min(bounds.x + bounds.width, deviceInfo.widthGrid) - max(bounds.x, 0)
                val visibleHeight = min(bounds.y + bounds.height, deviceInfo.heightGrid) - max(bounds.y, 0)
                visibleWidth > 0 && visibleHeight > 0
            } == true
            val hitAtCenter = element?.bounds?.center()?.let { center ->
                hierarchy.getElementAt(root, center.x, center.y)
            }
            val visibleAtCenter = inheritedFromChild || (hitAtCenter != null &&
                (node === hitAtCenter || (node.clickable == true && node.aggregate().any { it === hitAtCenter })))
            val include = element != null &&
                visibleGeometry &&
                node.enabled != false &&
                attrs["visible-to-user"] != "false" &&
                !claimedByClickableParent &&
                rawLabel != null &&
                visibleAtCenter

            if (include) {
                val bounds = element!!.bounds
                val sensitive = isSensitiveNode(node)
                val label = redact(rawLabel.value, sensitive = sensitive && !rawLabel.semantic)
                if (label.isNotBlank()) {
                    val id = "c" + (candidates.size + 1)
                    candidates += JevCandidateTarget(
                        payload = JevCandidatePayload(
                            id = id,
                            label = label,
                            role = redact(role),
                            resourceId = resourceId?.let { redact(it, SENSITIVE.containsMatchIn(it)) },
                            bounds = "[" + bounds.x + "," + bounds.y + "][" + (bounds.x + bounds.width) + "," + (bounds.y + bounds.height) + "]",
                            enabled = true,
                            visible = true,
                        ),
                        element = element,
                    )
                }
            }

            val childClaimed = claimedByClickableParent || clickableParentClaimsChildren
            node.children.forEach { child -> visit(child, childClaimed) }
        }

        visit(root, claimedByClickableParent = false)
        return candidates
    }

    fun selectorDescription(description: String): String =
        redact(description, sensitive = SENSITIVE.containsMatchIn(description))

    private fun nodeLabel(node: TreeNode): RawLabel? {
        val attrs = node.attributes
        val sensitive = isSensitiveNode(node)

        SEMANTIC_ATTRIBUTES.forEach { key ->
            attrs[key]?.takeIf { it.isNotBlank() }?.let { return RawLabel(it, semantic = true) }
        }

        val text = attrs["text"]?.takeIf { it.isNotBlank() }
        if (text != null) {
            if (!sensitive) return RawLabel(text, semantic = false)
            if (SAFE_SECRET_LABEL.matches(text.trim())) return RawLabel(text, semantic = true)
            return RawLabel("[redacted]", semantic = true)
        }

        return null
    }

    private fun isSensitiveNode(node: TreeNode): Boolean {
        val attrs = node.attributes
        val role = attrs["class"].orEmpty()
        return TEXT_INPUT_ROLE.containsMatchIn(role) ||
            SENSITIVE.containsMatchIn(role) ||
            SENSITIVE.containsMatchIn(attrs["resource-id"].orEmpty()) ||
            attrs.any { (key, value) ->
                (SENSITIVE.containsMatchIn(key) || SENSITIVE.containsMatchIn(value)) &&
                    key.lowercase() !in setOf("text", "contentdescription", "accessibilitytext", "hinttext")
            }
    }

    private fun isActionableRole(role: String): Boolean {
        val normalized = role.substringAfterLast('.').lowercase()
        return normalized in setOf(
            "button",
            "checkbox",
            "imagebutton",
            "radiobutton",
            "switch",
            "togglebutton",
            "spinner",
            "seekbar",
            "tab",
        )
    }

    private fun redact(value: String, sensitive: Boolean = false): String {
        if (sensitive || SECRET_VALUE.matches(value.trim())) return "[redacted]"
        return value
            .replace(SECRET_ASSIGNMENT, "[redacted]")
            .replace(EMAIL, "[redacted-email]")
            .replace(BEARER, "[redacted-token]")
            .replace(LONG_SECRET, "[redacted-token]")
            .replace(Regex("\\s+"), " ")
            .take(120)
    }
}
