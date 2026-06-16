# SCMBOT Agent Architecture Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor SCMBOT into a clearer `Chat UI -> Session Controller -> Agent Runtime -> Tool Registry` architecture while preserving current chat and order-draft behavior.

**Architecture:** Add a thin session controller between `AgentChatFragment` and the existing runtime/order code. Keep normal Q&A on the `AgentPlanner + AgentToolRegistry + AgentTool` path, move order draft orchestration out of the Fragment, and add per-tool timeout/error isolation without introducing streaming, multi-session UI, or LLM-controlled tool calling.

**Tech Stack:** Kotlin, Android Fragments, coroutines, JUnit4, existing `AgentRuntime`, `AgentToolRegistry`, `AgentHistoryStore`, SCM market clients.

---

## File Structure

- Modify: `app/src/main/java/com/euedrc/bugsc/agent/AgentHermesModels.kt`
  - Add timeout support to `AgentToolRegistry`.
  - Keep `AgentTool` and `AgentToolResult` as the normal Q&A tool contract.
- Create: `app/src/main/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinator.kt`
  - Own order parsing, follow-up merge, item/address resolution, SCM login gate, and confirmed order creation.
- Create: `app/src/main/java/com/euedrc/bugsc/agent/AgentSessionController.kt`
  - Own conversation state, history writes, runtime invocation, order draft pending state, and UI events.
- Modify: `app/src/main/java/com/euedrc/bugsc/agent/AgentRuntime.kt`
  - Make the tool-based path the primary path and remove new code dependence on `AgentSkillRegistry`.
- Modify: `app/src/main/java/com/euedrc/bugsc/agent/AgentChatFragment.kt`
  - Delegate business flow to `AgentSessionController`.
  - Keep message rendering and order action button rendering.
- Test: `app/src/test/java/com/euedrc/bugsc/agent/AgentToolRegistryTest.kt`
  - Add timeout and isolation coverage.
- Test: `app/src/test/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinatorTest.kt`
  - Cover migrated order behavior.
- Test: `app/src/test/java/com/euedrc/bugsc/agent/AgentSessionControllerTest.kt`
  - Cover history, settings gate, stale response, and pending order state.
- Modify: `app/src/test/java/com/euedrc/bugsc/agent/AgentRuntimeTest.kt`
  - Move runtime tests to the tool-based path.
- Keep: `app/src/main/java/com/euedrc/bugsc/agent/AgentSkill.kt`, `AgentSkillRegistry.kt`, `DataSkills.kt`
  - Leave in place for compatibility during this refactor, but do not use them in new Fragment/session code.

## Task 1: Add Tool Timeout and Failure Isolation

**Files:**
- Modify: `app/src/main/java/com/euedrc/bugsc/agent/AgentHermesModels.kt`
- Create or modify: `app/src/test/java/com/euedrc/bugsc/agent/AgentToolRegistryTest.kt`

- [ ] **Step 1: Write failing tests for timeout and partial failure**

Add these tests to `app/src/test/java/com/euedrc/bugsc/agent/AgentToolRegistryTest.kt`:

```kotlin
package com.euedrc.bugsc.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRegistryTest {

    private class FastTool : AgentTool {
        override val name: String = "fast"
        override val description: String = "Fast tool"

        override suspend fun run(call: AgentToolCall): AgentToolResult =
            AgentToolResult(
                call = call,
                summary = "fast result",
                facts = listOf(AgentFact("result", "fast")),
                sources = listOf(AgentSource("fast", "test")),
                confidence = 0.9f,
            )
    }

    private class SlowTool : AgentTool {
        override val name: String = "slow"
        override val description: String = "Slow tool"

        override suspend fun run(call: AgentToolCall): AgentToolResult {
            delay(200)
            return AgentToolResult(
                call = call,
                summary = "slow result",
                facts = emptyList(),
                sources = listOf(AgentSource("slow", "test")),
                confidence = 0.5f,
            )
        }
    }

    private class ThrowingTool : AgentTool {
        override val name: String = "throwing"
        override val description: String = "Throwing tool"

        override suspend fun run(call: AgentToolCall): AgentToolResult {
            error("boom")
        }
    }

    @Test
    fun oneFailingToolDoesNotBlockSuccessfulTool() = runBlocking {
        val registry = AgentToolRegistry(
            tools = listOf(FastTool(), ThrowingTool()),
            timeoutMillis = 1_000,
        )

        val results = registry.execute(
            listOf(
                AgentToolCall("fast", mapOf("term" to "x")),
                AgentToolCall("throwing", mapOf("term" to "x")),
            ),
        )

        assertEquals(2, results.size)
        assertEquals("fast result", results.single { it.call.tool == "fast" }.summary)
        assertNotNull(results.single { it.call.tool == "throwing" }.error)
    }

    @Test
    fun timedOutToolReturnsErrorResult() = runBlocking {
        val registry = AgentToolRegistry(
            tools = listOf(SlowTool(), FastTool()),
            timeoutMillis = 50,
        )

        val results = registry.execute(
            listOf(
                AgentToolCall("slow", mapOf("term" to "x")),
                AgentToolCall("fast", mapOf("term" to "x")),
            ),
        )

        val slow = results.single { it.call.tool == "slow" }
        assertEquals(0f, slow.confidence)
        assertTrue(slow.summary.contains("暂不可用"))
        assertTrue(slow.error.orEmpty().contains("timeout"))
        assertEquals("fast result", results.single { it.call.tool == "fast" }.summary)
    }

    @Test
    fun unregisteredToolReturnsErrorResult() = runBlocking {
        val registry = AgentToolRegistry(tools = emptyList(), timeoutMillis = 1_000)

        val result = registry.execute(listOf(AgentToolCall("missing", emptyMap()))).single()

        assertEquals("missing", result.call.tool)
        assertEquals(0f, result.confidence)
        assertEquals("tool not registered", result.error)
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.AgentToolRegistryTest'
```

Expected: compile failure because `AgentToolRegistry` does not accept `timeoutMillis`, or test failure because slow tools do not time out.

- [ ] **Step 3: Implement timeout in `AgentToolRegistry`**

Replace `AgentToolRegistry` in `app/src/main/java/com/euedrc/bugsc/agent/AgentHermesModels.kt` with:

```kotlin
class AgentToolRegistry(
    tools: List<AgentTool>,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val toolMap = tools.associateBy { it.name }

    suspend fun execute(calls: List<AgentToolCall>): List<AgentToolResult> = coroutineScope {
        calls.map { call ->
            async {
                val tool = toolMap[call.tool]
                if (tool == null) {
                    AgentToolResult(
                        call = call,
                        summary = "${call.tool} 暂不可用",
                        facts = emptyList(),
                        sources = listOf(AgentSource(call.tool, "tool")),
                        confidence = 0f,
                        error = "tool not registered",
                    )
                } else {
                    runCatching { kotlinx.coroutines.withTimeout(timeoutMillis) { tool.run(call) } }
                        .getOrElse { error ->
                            AgentToolResult(
                                call = call,
                                summary = "${tool.description} 暂不可用",
                                facts = emptyList(),
                                sources = listOf(AgentSource(tool.description, "tool")),
                                confidence = 0f,
                                error = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                                    "timeout after ${timeoutMillis}ms"
                                } else {
                                    error.message ?: error::class.java.simpleName
                                },
                            )
                        }
                }
            }
        }.awaitAll()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.AgentToolRegistryTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/euedrc/bugsc/agent/AgentHermesModels.kt app/src/test/java/com/euedrc/bugsc/agent/AgentToolRegistryTest.kt
git commit -m "[fix] 增加SCMBOT工具超时隔离"
```

## Task 2: Extract Order Draft Coordinator

**Files:**
- Create: `app/src/main/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinator.kt`
- Create: `app/src/test/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinatorTest.kt`

- [ ] **Step 1: Write failing coordinator tests**

Create `app/src/test/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinatorTest.kt`:

```kotlin
package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.ItemSearchResult
import com.euedrc.bugsc.market.publish.MarketPublishJson
import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishItemValue
import com.euedrc.bugsc.market.transaction.AddressNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrderDraftCoordinatorTest {

    private val item = ItemSearchResult(
        id = 42,
        itemName = "绝杀步枪",
        itemNameEn = "Killshot Rifle",
        thumbnailUrl = "",
        thumbnailUrlHd = "",
    )

    private val address = AddressNode(
        id = 7L,
        parentId = 0L,
        name = "死局空间站",
    )

    @Test
    fun parseOrMergeKeepsPendingAndMergesFollowUp() {
        val coordinator = coordinator()
        val pending = ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000")

        val merged = coordinator.parseOrMerge("死局", pending)

        assertTrue(merged.isOrderIntent)
        assertEquals("死局", merged.locationKeyword)
        assertEquals("绝杀步枪", merged.itemKeyword)
    }

    @Test
    fun resolveRequiresLoginBeforeRemoteResolution() {
        val coordinator = coordinator(isLoggedIn = false)

        val result = coordinator.resolve(ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000 地点 死局"))

        assertTrue(result is AgentOrderDraftResult.NeedMoreInfo)
        assertTrue((result as AgentOrderDraftResult.NeedMoreInfo).message.contains("登录 SCM"))
    }

    @Test
    fun resolveReturnsConfirmationWhenFieldsAreValid() {
        val coordinator = coordinator()

        val result = coordinator.resolve(ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000 地点 死局"))

        assertTrue(result is AgentOrderDraftResult.ReadyToConfirm)
        val ready = result as AgentOrderDraftResult.ReadyToConfirm
        assertTrue(ready.message.contains("订单草稿"))
        assertEquals(item, ready.draft.item)
        assertEquals(address, ready.draft.location)
    }

    @Test
    fun createOrderReturnsCreatedOrderNumber() {
        val created = mutableListOf<List<PublishItemValue>>()
        val coordinator = coordinator(
            createOrder = { _, _, _, _, items -> created += items },
            findCreatedOrderNumber = { _, _ -> "SCM-1001" },
        )
        val ready = coordinator.resolve(ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000 地点 死局")) as AgentOrderDraftResult.ReadyToConfirm

        val result = coordinator.create(ready.draft)

        assertTrue(result is AgentOrderDraftCreateResult.Created)
        assertEquals("SCM-1001", (result as AgentOrderDraftCreateResult.Created).orderNumber)
        assertEquals(1, created.single().single().quantity)
    }

    @Test
    fun createOrderFailureReturnsFailureResult() {
        val coordinator = coordinator(
            createOrder = { _, _, _, _, _ -> error("network down") },
        )
        val ready = coordinator.resolve(ScmOrderDraftParser.parse("出售 绝杀步枪 1 个 单价 50000 地点 死局")) as AgentOrderDraftResult.ReadyToConfirm

        val result = coordinator.create(ready.draft)

        assertTrue(result is AgentOrderDraftCreateResult.Failed)
        assertTrue((result as AgentOrderDraftCreateResult.Failed).message.contains("network down"))
    }

    private fun coordinator(
        isLoggedIn: Boolean = true,
        createOrder: (PublishCreatorType, Long, com.euedrc.bugsc.market.publish.PublishOrderStatus, com.euedrc.bugsc.market.publish.PublishExpireTime, List<PublishItemValue>) -> Unit = { _, _, _, _, _ -> },
        findCreatedOrderNumber: (PublishCreatorType, String) -> String = { _, _ -> "" },
    ): AgentOrderDraftCoordinator =
        AgentOrderDraftCoordinator(
            isLoggedIn = { isLoggedIn },
            itemSearch = { keyword -> if (keyword.contains("绝杀")) listOf(item) else emptyList() },
            addressList = { listOf(address) },
            createOrder = createOrder,
            findCreatedOrderNumber = findCreatedOrderNumber,
        )
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.AgentOrderDraftCoordinatorTest'
```

Expected: compile failure because `AgentOrderDraftCoordinator`, `AgentOrderDraftResult`, and `AgentOrderDraftCreateResult` do not exist.

- [ ] **Step 3: Implement coordinator**

Create `app/src/main/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinator.kt`:

```kotlin
package com.euedrc.bugsc.agent

import com.euedrc.bugsc.market.publish.PublishCreatorType
import com.euedrc.bugsc.market.publish.PublishExpireTime
import com.euedrc.bugsc.market.publish.PublishItemValue
import com.euedrc.bugsc.market.publish.PublishOrderStatus

sealed class AgentOrderDraftResult {
    data class NotOrder(val parsed: ScmOrderDraftParseResult) : AgentOrderDraftResult()
    data class NeedMoreInfo(val parsed: ScmOrderDraftParseResult, val message: String) : AgentOrderDraftResult()
    data class ReadyToConfirm(val draft: ScmOrderDraftResolution.Resolved, val message: String) : AgentOrderDraftResult()
}

sealed class AgentOrderDraftCreateResult {
    data class Created(val orderNumber: String) : AgentOrderDraftCreateResult()
    data class Failed(val message: String) : AgentOrderDraftCreateResult()
}

class AgentOrderDraftCoordinator(
    private val isLoggedIn: () -> Boolean,
    private val itemSearch: (String) -> List<com.euedrc.bugsc.market.publish.ItemSearchResult>,
    private val addressList: () -> List<com.euedrc.bugsc.market.transaction.AddressNode>,
    private val createOrder: (PublishCreatorType, Long, PublishOrderStatus, PublishExpireTime, List<PublishItemValue>) -> Unit,
    private val findCreatedOrderNumber: (PublishCreatorType, String) -> String,
) {

    fun parseOrMerge(
        text: String,
        pendingParse: ScmOrderDraftParseResult?,
    ): ScmOrderDraftParseResult =
        if (pendingParse == null) {
            ScmOrderDraftParser.parse(text)
        } else {
            ScmOrderDraftParser.mergeFollowUp(pendingParse, text)
        }

    fun resolve(parsed: ScmOrderDraftParseResult): AgentOrderDraftResult {
        if (!parsed.isOrderIntent) return AgentOrderDraftResult.NotOrder(parsed)
        if (!isLoggedIn()) {
            return AgentOrderDraftResult.NeedMoreInfo(parsed, "需要先登录 SCM，才能创建订单。")
        }
        return when (
            val resolution = ScmOrderDraftResolver(
                itemSearch = itemSearch,
                addressList = addressList,
            ).resolve(parsed)
        ) {
            is ScmOrderDraftResolution.NeedMoreInfo -> AgentOrderDraftResult.NeedMoreInfo(parsed, resolution.message)
            is ScmOrderDraftResolution.Resolved -> AgentOrderDraftResult.ReadyToConfirm(
                draft = resolution,
                message = resolution.confirmationMarkdown(),
            )
        }
    }

    fun create(draft: ScmOrderDraftResolution.Resolved): AgentOrderDraftCreateResult =
        runCatching {
            val item = PublishItemValue(
                item = draft.item,
                quantity = draft.parsed.quantity,
                price = requireNotNull(draft.parsed.unitPrice),
                quality = null,
            )
            createOrder(
                requireNotNull(draft.parsed.creatorType),
                draft.location.id,
                draft.parsed.status,
                draft.parsed.expireTime,
                listOf(item),
            )
            AgentOrderDraftCreateResult.Created(
                findCreatedOrderNumber(requireNotNull(draft.parsed.creatorType), draft.item.itemName),
            )
        }.getOrElse { error ->
            AgentOrderDraftCreateResult.Failed(error.message ?: "SCM 请求失败")
        }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.AgentOrderDraftCoordinatorTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinator.kt app/src/test/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinatorTest.kt
git commit -m "[feat] 抽出SCMBOT订单草稿协调器"
```

## Task 3: Add Agent Session Controller

**Files:**
- Create: `app/src/main/java/com/euedrc/bugsc/agent/AgentSessionController.kt`
- Create: `app/src/test/java/com/euedrc/bugsc/agent/AgentSessionControllerTest.kt`

- [ ] **Step 1: Write failing session controller tests**

Create `app/src/test/java/com/euedrc/bugsc/agent/AgentSessionControllerTest.kt`:

```kotlin
package com.euedrc.bugsc.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionControllerTest {

    private class MemoryKvStore : AgentSettingsKvStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FakeRuntime(private val answer: suspend (String, List<AgentMessage>) -> String) : AgentAnswerRuntime {
        override suspend fun answer(text: String, history: List<AgentMessage>): String = answer.invoke(text, history)
    }

    @Test
    fun unconfiguredSettingsNavigatesToSettingsWithoutSavingUserMessage() = runBlocking {
        val history = AgentHistoryStore(MemoryKvStore())
        val controller = controller(
            settings = AgentSettings(apiKey = "", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
            historyStore = history,
        )

        val events = controller.sendUserText("量子矿哪里采")

        assertTrue(events.any { it is AgentSessionEvent.NavigateSettingsRequired })
        assertTrue(history.load().isEmpty())
    }

    @Test
    fun normalAnswerAppendsUserAndAssistantMessages() = runBlocking {
        val history = AgentHistoryStore(MemoryKvStore())
        val controller = controller(
            historyStore = history,
            runtime = FakeRuntime { text, _ -> "回答：$text" },
        )

        val events = controller.sendUserText("量子矿哪里采")

        assertTrue(events.any { it is AgentSessionEvent.AppendMessage })
        assertEquals(listOf(AgentMessageRole.USER, AgentMessageRole.ASSISTANT), history.load().map { it.role })
        assertEquals("回答：量子矿哪里采", history.load().last().content)
    }

    @Test
    fun runtimeFailureAppendsReadableFailureMessage() = runBlocking {
        val history = AgentHistoryStore(MemoryKvStore())
        val controller = controller(
            historyStore = history,
            runtime = FakeRuntime { _, _ -> error("network down") },
        )

        controller.sendUserText("量子矿哪里采")

        assertEquals(2, history.load().size)
        assertTrue(history.load().last().content.contains("network down"))
    }

    @Test
    fun newChatPreventsOldAnswerFromWritingCurrentHistory() = runBlocking {
        val pending = CompletableDeferred<String>()
        val history = AgentHistoryStore(MemoryKvStore())
        val controller = controller(
            historyStore = history,
            runtime = FakeRuntime { _, _ -> pending.await() },
        )

        val job = async { controller.sendUserText("旧问题") }
        controller.startNewChat()
        pending.complete("旧回答")
        job.await()

        assertTrue(history.load().isEmpty())
    }

    private fun controller(
        settings: AgentSettings = AgentSettings("sk-test", AgentSettingsStore.MODEL_DEEPSEEK_FLASH),
        historyStore: AgentHistoryStore = AgentHistoryStore(MemoryKvStore()),
        runtime: AgentAnswerRuntime = FakeRuntime { _, _ -> "ok" },
        orderCoordinator: AgentOrderDraftCoordinator = AgentOrderDraftCoordinator(
            isLoggedIn = { true },
            itemSearch = { emptyList() },
            addressList = { emptyList() },
            createOrder = { _, _, _, _, _ -> },
            findCreatedOrderNumber = { _, _ -> "" },
        ),
    ): AgentSessionController =
        AgentSessionController(
            settingsProvider = { settings },
            historyStore = historyStore,
            runtime = runtime,
            orderCoordinator = orderCoordinator,
            nowMillis = { 100L },
            idProvider = object : AgentMessageIdProvider {
                private var next = 0
                override fun nextId(): String = "msg-${next++}"
            },
        )
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.AgentSessionControllerTest'
```

Expected: compile failure because `AgentSessionController`, `AgentSessionEvent`, `AgentAnswerRuntime`, and `AgentMessageIdProvider` do not exist.

- [ ] **Step 3: Implement session controller**

Create `app/src/main/java/com/euedrc/bugsc/agent/AgentSessionController.kt`:

```kotlin
package com.euedrc.bugsc.agent

import java.util.UUID

interface AgentAnswerRuntime {
    suspend fun answer(text: String, history: List<AgentMessage>): String
}

class RuntimeAgentAnswerRuntime(private val runtimeFactory: () -> AgentRuntime) : AgentAnswerRuntime {
    override suspend fun answer(text: String, history: List<AgentMessage>): String =
        runtimeFactory().answer(text, history)
}

interface AgentMessageIdProvider {
    fun nextId(): String
}

object UuidAgentMessageIdProvider : AgentMessageIdProvider {
    override fun nextId(): String = UUID.randomUUID().toString()
}

sealed class AgentSessionEvent {
    data class RenderMessages(val messages: List<AgentMessage>) : AgentSessionEvent()
    data class AppendMessage(val message: AgentMessage) : AgentSessionEvent()
    data class ShowOrderActions(val draft: ScmOrderDraftResolution.Resolved) : AgentSessionEvent()
    data class ShowToast(val message: String) : AgentSessionEvent()
    data object NavigateSettingsRequired : AgentSessionEvent()
}

class AgentSessionController(
    private val settingsProvider: () -> AgentSettings,
    private val historyStore: AgentHistoryStore,
    private val runtime: AgentAnswerRuntime,
    private val orderCoordinator: AgentOrderDraftCoordinator,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val idProvider: AgentMessageIdProvider = UuidAgentMessageIdProvider,
) {
    private var conversationVersion: Long = 0L
    private var pendingOrderDraft: ScmOrderDraftResolution.Resolved? = null
    private var pendingOrderParse: ScmOrderDraftParseResult? = null

    fun loadHistory(): List<AgentSessionEvent> =
        listOf(AgentSessionEvent.RenderMessages(historyStore.load()))

    fun startNewChat(): List<AgentSessionEvent> {
        conversationVersion += 1L
        pendingOrderDraft = null
        pendingOrderParse = null
        historyStore.clear()
        return listOf(
            AgentSessionEvent.RenderMessages(emptyList()),
            AgentSessionEvent.ShowToast("已开启新对话"),
        )
    }

    suspend fun sendUserText(text: String): List<AgentSessionEvent> {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return emptyList()
        val parsed = orderCoordinator.parseOrMerge(cleanText, pendingOrderParse)
        if (parsed.isOrderIntent) return handleOrderDraft(cleanText, parsed)
        if (!settingsProvider().isConfigured) {
            return listOf(AgentSessionEvent.NavigateSettingsRequired)
        }
        val version = conversationVersion
        val historyBeforeSend = historyStore.load()
        val userMessage = message(AgentMessageRole.USER, cleanText)
        historyStore.append(userMessage)
        val events = mutableListOf<AgentSessionEvent>(AgentSessionEvent.AppendMessage(userMessage))
        val answer = runCatching { runtime.answer(cleanText, historyBeforeSend) }
            .getOrElse { it.message ?: "SCMBOT 请求失败" }
        if (version != conversationVersion) return emptyList()
        val assistantMessage = message(AgentMessageRole.ASSISTANT, answer)
        historyStore.append(assistantMessage)
        events += AgentSessionEvent.AppendMessage(assistantMessage)
        return events
    }

    fun confirmPendingOrder(): List<AgentSessionEvent> {
        val draft = pendingOrderDraft ?: return appendAssistant("这个订单草稿已失效，请重新发起创建。")
        return when (val result = orderCoordinator.create(draft)) {
            is AgentOrderDraftCreateResult.Created -> {
                pendingOrderDraft = null
                pendingOrderParse = null
                appendAssistant(
                    if (result.orderNumber.isBlank()) {
                        "订单已创建，可在“我的挂单”查看。"
                    } else {
                        "订单已创建：${result.orderNumber}"
                    },
                )
            }
            is AgentOrderDraftCreateResult.Failed -> appendAssistant("创建失败：${result.message}")
        }
    }

    fun cancelPendingOrder(): List<AgentSessionEvent> {
        pendingOrderDraft = null
        pendingOrderParse = null
        return appendAssistant("已取消创建订单。")
    }

    private fun handleOrderDraft(text: String, parsed: ScmOrderDraftParseResult): List<AgentSessionEvent> {
        val userMessage = message(AgentMessageRole.USER, text)
        historyStore.append(userMessage)
        val events = mutableListOf<AgentSessionEvent>(AgentSessionEvent.AppendMessage(userMessage))
        when (val result = orderCoordinator.resolve(parsed)) {
            is AgentOrderDraftResult.NotOrder -> Unit
            is AgentOrderDraftResult.NeedMoreInfo -> {
                pendingOrderParse = result.parsed
                pendingOrderDraft = null
                events += appendAssistant(result.message)
            }
            is AgentOrderDraftResult.ReadyToConfirm -> {
                pendingOrderParse = null
                pendingOrderDraft = result.draft
                events += appendAssistant(result.message)
                events += AgentSessionEvent.ShowOrderActions(result.draft)
            }
        }
        return events
    }

    private fun appendAssistant(text: String): List<AgentSessionEvent> {
        val assistantMessage = message(AgentMessageRole.ASSISTANT, text)
        historyStore.append(assistantMessage)
        return listOf(AgentSessionEvent.AppendMessage(assistantMessage))
    }

    private fun message(role: AgentMessageRole, content: String): AgentMessage =
        AgentMessage(
            id = idProvider.nextId(),
            role = role,
            content = content,
            createdAt = nowMillis(),
            status = AgentMessageStatus.SENT,
        )
}
```

- [ ] **Step 4: Run session tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.AgentSessionControllerTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/euedrc/bugsc/agent/AgentSessionController.kt app/src/test/java/com/euedrc/bugsc/agent/AgentSessionControllerTest.kt
git commit -m "[feat] 增加SCMBOT会话控制器"
```

## Task 4: Move AgentChatFragment to Session Controller

**Files:**
- Modify: `app/src/main/java/com/euedrc/bugsc/agent/AgentChatFragment.kt`
- Test: existing agent unit tests

- [ ] **Step 1: Add runtime and order coordinator builders to the Fragment**

In `AgentChatFragment`, add a controller property:

```kotlin
private lateinit var sessionController: AgentSessionController
```

In `onViewCreated`, after stores are initialized, assign:

```kotlin
sessionController = buildSessionController()
```

Add this builder method:

```kotlin
private fun buildSessionController(): AgentSessionController {
    val index = localProvider.entityIndex()
    return AgentSessionController(
        settingsProvider = { settingsStore.settings() },
        historyStore = historyStore,
        runtime = RuntimeAgentAnswerRuntime {
            AgentRuntime(
                analyzer = QueryAnalyzer(index),
                promptBuilder = AgentPromptBuilder(profile),
                deepSeekClient = DeepSeekClient(UrlConnectionDeepSeekTransport()),
                settingsProvider = { settingsStore.settings() },
                planner = AgentPlanner(AgentSkillCardProvider.defaultCards()),
                toolRegistry = AgentToolRegistry(
                    AgentLocalSearchTools.create(localProvider, index) +
                        ScmAgentTools.create(entityIndex = index),
                ),
            )
        },
        orderCoordinator = AgentOrderDraftCoordinator(
            isLoggedIn = { ScmAuthStore.isLoggedIn },
            itemSearch = { keyword ->
                ScmSearchTermExpander.expand(keyword, index)
                    .asSequence()
                    .map { marketPublishClient.searchItems(it) }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            },
            addressList = { transactionClient.addressList() },
            createOrder = { creatorType, locationId, status, expireTime, items ->
                marketPublishClient.createOrder(
                    creatorType = creatorType,
                    locationId = locationId,
                    status = status,
                    expireTime = expireTime,
                    items = items,
                )
            },
            findCreatedOrderNumber = { creatorType, itemName ->
                marketPublishClient.ownOrders(1, creatorType = creatorType)
                    .let { MarketPublishJson.findCreatedOrderNumber(it, creatorType, itemName) }
            },
        ),
    )
}
```

- [ ] **Step 2: Replace history rendering with controller load**

Change `renderHistory()` to:

```kotlin
private fun renderHistory() {
    container.removeAllViews()
    val events = if (::sessionController.isInitialized) {
        sessionController.loadHistory()
    } else {
        listOf(AgentSessionEvent.RenderMessages(historyStore.load()))
    }
    if (historyStore.load().isEmpty()) {
        addBubble(profile.roleDescription, mine = false)
    }
    handleSessionEvents(events)
    scrollToBottom()
}
```

Add event handling:

```kotlin
private fun handleSessionEvents(events: List<AgentSessionEvent>) {
    events.forEach { event ->
        when (event) {
            is AgentSessionEvent.RenderMessages -> {
                container.removeAllViews()
                if (event.messages.isEmpty()) {
                    addBubble(profile.roleDescription, mine = false)
                } else {
                    event.messages.forEach { addBubble(it.content, mine = it.role == AgentMessageRole.USER) }
                }
            }
            is AgentSessionEvent.AppendMessage -> addBubble(event.message.content, mine = event.message.role == AgentMessageRole.USER)
            is AgentSessionEvent.ShowOrderActions -> addOrderActions()
            is AgentSessionEvent.ShowToast -> Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
            AgentSessionEvent.NavigateSettingsRequired -> {
                Toast.makeText(requireContext(), R.string.agent_input_config_hint, Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.AgentSettingsFragment)
            }
        }
    }
    scrollToBottom()
}
```

- [ ] **Step 3: Replace `sendMessage()` with controller delegation**

Replace `sendMessage()` body with:

```kotlin
private fun sendMessage() {
    val text = input.text.toString().trim()
    if (text.isBlank()) return
    input.setText("")
    lifecycleScope.launch {
        val events = withContext(Dispatchers.IO) {
            sessionController.sendUserText(text)
        }
        if (!isAdded) return@launch
        handleSessionEvents(events)
    }
}
```

- [ ] **Step 4: Replace new chat and order action methods**

Replace `startNewChat()` body with:

```kotlin
private fun startNewChat() {
    input.setText("")
    handleSessionEvents(sessionController.startNewChat())
}
```

Replace `addOrderActions` signature and body:

```kotlin
private fun addOrderActions() {
    val row = LinearLayout(requireContext()).apply {
        gravity = Gravity.START
        setPadding(0, dp(4), 0, dp(6))
    }
    val confirm = Button(requireContext()).apply {
        text = "确认创建"
        isAllCaps = false
        setOnClickListener {
            row.visibility = View.GONE
            lifecycleScope.launch {
                val events = withContext(Dispatchers.IO) { sessionController.confirmPendingOrder() }
                if (isAdded) handleSessionEvents(events)
            }
        }
    }
    val cancel = Button(requireContext()).apply {
        text = "取消"
        isAllCaps = false
        setOnClickListener {
            row.visibility = View.GONE
            handleSessionEvents(sessionController.cancelPendingOrder())
        }
    }
    row.addView(confirm)
    row.addView(cancel)
    container.addView(row)
}
```

Delete these now-unused Fragment members and methods:

```kotlin
private var conversationVersion: Long = 0L
private var pendingOrderDraft: ScmOrderDraftResolution.Resolved? = null
private var pendingOrderParse: ScmOrderDraftParseResult? = null
private fun sendOrderDraftMessage(text: String, parsed: ScmOrderDraftParseResult) { ... }
private fun buildRuntime(): AgentRuntime { ... }
private fun createPendingOrder(draft: ScmOrderDraftResolution.Resolved) { ... }
```

- [ ] **Step 5: Run compile-focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'
```

Expected: PASS for unit tests. If Android-only Fragment compile errors remain hidden by unit tests, run the assemble command in Step 6.

- [ ] **Step 6: Run assemble**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/euedrc/bugsc/agent/AgentChatFragment.kt
git commit -m "[optimize] 收敛SCMBOT聊天页职责"
```

## Task 5: Move Runtime Tests to Tool-Based Path

**Files:**
- Modify: `app/src/test/java/com/euedrc/bugsc/agent/AgentRuntimeTest.kt`

- [ ] **Step 1: Replace skill-based runtime fixtures**

Replace the private `FixedSkill` helper in `AgentRuntimeTest` with:

```kotlin
private class FixedTool(
    override val name: String,
    private val result: AgentToolResult,
) : AgentTool {
    override val description: String = name

    override suspend fun run(call: AgentToolCall): AgentToolResult =
        result.copy(call = call)
}
```

Replace `runtime(result: SkillResult, modelContent: String)` with:

```kotlin
private fun runtime(result: AgentToolResult, modelContent: String): AgentRuntime =
    AgentRuntime(
        analyzer = QueryAnalyzer(),
        planner = AgentPlanner(
            listOf(
                AgentSkillCard(
                    id = "test",
                    title = "test",
                    matchingIntents = setOf(AgentIntent.UNKNOWN),
                    workflow = "test workflow",
                    preferredTools = listOf(result.call.tool),
                ),
            ),
        ),
        toolRegistry = AgentToolRegistry(listOf(FixedTool(result.call.tool, result))),
        promptBuilder = AgentPromptBuilder(AgentProfileProvider.defaultProfile()),
        deepSeekClient = DeepSeekClient(FakeTransport(modelContent)),
        settingsProvider = { AgentSettings(apiKey = "sk-test", model = AgentSettingsStore.MODEL_DEEPSEEK_FLASH) },
    )
```

- [ ] **Step 2: Update test result builders**

Convert existing `SkillResult(...)` fixtures to `AgentToolResult(...)`. For example:

```kotlin
AgentToolResult(
    call = AgentToolCall("search_blueprint", mapOf("term" to "石英")),
    summary = "蓝图资料 未命中相关数据",
    facts = emptyList(),
    sources = listOf(AgentSource("蓝图资料", "local")),
    confidence = 0f,
)
```

For useful mining evidence:

```kotlin
AgentToolResult(
    call = AgentToolCall("search_mining", mapOf("term" to "量子矿")),
    summary = "Quantanium 可在 Lyria 采集",
    facts = listOf(AgentFact("地点", "Lyria")),
    sources = listOf(AgentSource("mining assets", "local")),
    confidence = 0.9f,
)
```

- [ ] **Step 3: Run runtime tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.AgentRuntimeTest'
```

Expected: PASS.

- [ ] **Step 4: Run broader agent tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'
```

Expected: PASS. If old `AgentSkillRegistryTest` still passes, leave it in place. Do not use `AgentSkillRegistry` from new session or Fragment code.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/euedrc/bugsc/agent/AgentRuntimeTest.kt
git commit -m "[optimize] 统一SCMBOT运行时测试主路径"
```

## Task 6: Final Verification and Scope Audit

**Files:**
- Inspect: `app/src/main/java/com/euedrc/bugsc/agent/AgentChatFragment.kt`
- Inspect: `app/src/main/java/com/euedrc/bugsc/agent/AgentRuntime.kt`
- Inspect: `app/src/main/java/com/euedrc/bugsc/agent/AgentHermesModels.kt`
- Inspect: `app/src/main/java/com/euedrc/bugsc/agent/AgentSessionController.kt`
- Inspect: `app/src/main/java/com/euedrc/bugsc/agent/AgentOrderDraftCoordinator.kt`

- [ ] **Step 1: Verify Fragment no longer owns business calls**

Run:

```bash
rg -n "ScmOrderDraftParser|ScmOrderDraftResolver|createOrder\\(|addressList\\(|conversationVersion|pendingOrderDraft|pendingOrderParse|DeepSeekClient\\(|AgentRuntime\\(" app/src/main/java/com/euedrc/bugsc/agent/AgentChatFragment.kt
```

Expected: no matches for parser, resolver, `createOrder(`, `addressList(`, `conversationVersion`, `pendingOrderDraft`, or `pendingOrderParse`. Matches for `DeepSeekClient(` and `AgentRuntime(` are acceptable only inside `buildSessionController()`.

- [ ] **Step 2: Verify no SCM chat coupling was introduced**

Run:

```bash
rg -n "ChatClient|ChatUnreadStore|ChatInboxSocket|ChatSocket" app/src/main/java/com/euedrc/bugsc/agent
```

Expected: no matches.

- [ ] **Step 3: Run agent unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.euedrc.bugsc.agent.*'
```

Expected: PASS.

- [ ] **Step 4: Run debug assemble**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Review git diff for scope**

Run:

```bash
git diff --stat HEAD~5..HEAD
```

Expected: changed files are limited to agent implementation/tests and this plan if it was committed separately. No SCM private chat files, navigation files, or layout files should be changed for this refactor.

- [ ] **Step 6: Commit any final cleanup**

If Task 6 caused cleanup changes, commit them:

```bash
git add app/src/main/java/com/euedrc/bugsc/agent app/src/test/java/com/euedrc/bugsc/agent
git commit -m "[optimize] 完成SCMBOT架构收敛验证"
```

If there are no cleanup changes, do not create an empty commit.

## Self-Review

- Spec coverage: The plan covers Fragment thinning, session controller, order coordinator migration, tool timeout isolation, tool-based runtime tests, stale response protection, and final verification. LLM-controlled tool calling remains documented as a future runtime strategy and is not implemented in this plan.
- Placeholder scan: The plan contains no unresolved placeholders. The only future-looking item is deliberately represented in the spec, not as an implementation step.
- Type consistency: `AgentSessionController` depends on `AgentAnswerRuntime`, `AgentOrderDraftCoordinator`, `AgentHistoryStore`, `AgentSettings`, and `AgentSessionEvent`. `AgentChatFragment` delegates to those types. `AgentToolRegistry` retains the existing `execute(calls)` call shape while adding an optional `timeoutMillis`.
