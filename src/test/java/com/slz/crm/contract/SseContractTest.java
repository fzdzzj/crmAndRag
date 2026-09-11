package com.slz.crm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.SseEventName;
import com.slz.crm.server.ai.AiChatSseEventWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 契约测试（task18：关键 API 契约固化）。
 *
 * <p>把助手 SSE 的<b>事件名 wire 值</b>与<b>每个事件的 payload 字段集</b>钉死，任何增删改名都会在此失败——
 * 这是前后端唯一对接面，漂移即破坏兼容。依据 {@link SseEventName} 枚举 Javadoc 与 contracts-frozen.md §4/§5。</p>
 *
 * <p>纯单元测试：直接驱动 {@link AiChatSseEventWriter} 的 payload 构造，不起 Spring 上下文、不依赖外部服务，
 * 本地与 CI 均可跑。usage 传 null 走 0 默认（契约关注字段名而非数值，数值路径由 C 的助手测试覆盖）。</p>
 */
class SseContractTest {

    private final AiChatSseEventWriter writer = new AiChatSseEventWriter();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 事件名 wire 值冻结：前端按这些字符串分发；全集恰好 12 个，多一个少一个都算契约变更。 */
    @Test
    void eventNameWireValuesAreFrozen() {
        assertEquals("start", SseEventName.START.wireName());
        assertEquals("meta", SseEventName.META.wireName());
        assertEquals("sources", SseEventName.SOURCES.wireName());
        assertEquals("thinking", SseEventName.THINKING.wireName());
        assertEquals("delta", SseEventName.DELTA.wireName());
        assertEquals("references", SseEventName.REFERENCES.wireName());
        assertEquals("title", SseEventName.TITLE.wireName());
        assertEquals("done", SseEventName.DONE.wireName());
        assertEquals("cancelled", SseEventName.CANCELLED.wireName());
        assertEquals("stopped", SseEventName.STOPPED.wireName());
        assertEquals("error", SseEventName.ERROR.wireName());
        assertEquals("ping", SseEventName.PING.wireName());
        assertEquals(12, SseEventName.values().length, "SSE 事件全集数量变化=契约变更，需同步前端与本文档");
    }

    @Test
    void startPayloadHasFrozenFields() throws Exception {
        JsonNode json = mapper.readTree(writer.toStartJson("s-1", 42L, "gen-1"));
        assertExactFields(json, "sessionId", "assistantMessageId", "generationId");
        assertEquals("s-1", json.get("sessionId").asText());
        assertEquals(42L, json.get("assistantMessageId").asLong());
        assertEquals("gen-1", json.get("generationId").asText());
    }

    @Test
    void metaPayloadHasFrozenFields() throws Exception {
        JsonNode json = mapper.readTree(writer.toMetaJson("dashscope", "qwen-max", true, false));
        assertExactFields(json, "provider", "model", "useKnowledgeBase", "thinking");
        assertTrue(json.get("useKnowledgeBase").asBoolean());
        assertFalse(json.get("thinking").asBoolean());
    }

    @Test
    void thinkingPayloadHasFrozenFields() throws Exception {
        JsonNode json = mapper.readTree(writer.toThinkingJson("推理中", false));
        assertExactFields(json, "text", "finished");
        assertFalse(json.get("finished").asBoolean());
    }

    @Test
    void deltaPayloadHasFrozenFields() throws Exception {
        JsonNode json = mapper.readTree(writer.toDeltaJson("增量"));
        assertExactFields(json, "content");
        assertEquals("增量", json.get("content").asText());
    }

    @Test
    void titlePayloadHasFrozenFields() throws Exception {
        JsonNode json = mapper.readTree(writer.toTitleJson("会话标题"));
        assertExactFields(json, "text");
    }

    /** done 必须携带 usage 三元组（token 计量核对依赖），cancelled 恒 false。 */
    @Test
    void donePayloadCarriesUsageTriple() throws Exception {
        JsonNode json = mapper.readTree(writer.toDoneJson("s-1", null));
        assertExactFields(json, "sessionId", "cancelled", "usage");
        assertFalse(json.get("cancelled").asBoolean());
        assertExactFields(json.get("usage"), "input", "output", "total");
    }

    @Test
    void cancelledAndStoppedCarryReason() throws Exception {
        assertExactFields(mapper.readTree(writer.toCancelledJson("USER_CANCEL")), "reason");
        assertExactFields(mapper.readTree(writer.toStoppedJson("SUPERSEDED_BY_NEW_REQUEST")), "reason");
    }

    /** references = citations（答案实际引用编号）+ items（业务实体），档 B 只高亮承重来源。 */
    @Test
    void referencesPayloadCarriesCitationsAndItems() throws Exception {
        JsonNode json = mapper.readTree(writer.toReferencesJson(List.of(), List.of(1, 3)));
        assertExactFields(json, "citations", "items");
        assertEquals(2, json.get("citations").size());
        assertTrue(json.get("items").isArray());
    }

    /** sources 每条必须含档 B 高亮锚点 chunkIndex/pageNo/chunkId（前端跳页高亮依赖）。 */
    @Test
    void sourcesPayloadCarriesHighlightAnchors() throws Exception {
        SourceReference ref = new SourceReference("pdf", "hybrid", "sales.pdf", "doc-1",
                "chunk-9", 9, 3, null, "片段摘录", 0.87);
        JsonNode json = mapper.readTree(writer.toSourcesJson(List.of(ref)));
        assertTrue(json.isArray());
        JsonNode item = json.get(0);
        assertExactFields(item, "sourceType", "route", "filename", "documentId", "chunkId",
                "chunkIndex", "pageNo", "rowIndex", "excerpt", "relevanceScore");
        assertEquals(9, item.get("chunkIndex").asInt());
        assertEquals(3, item.get("pageNo").asInt());
        assertEquals("chunk-9", item.get("chunkId").asText());
    }

    /** 断言 JSON 对象字段集与冻结契约完全一致（多字段或少字段都失败）。 */
    private void assertExactFields(JsonNode node, String... expected) {
        Set<String> actual = new TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(new TreeSet<>(List.of(expected)), actual, "payload 字段集与冻结契约不一致");
    }
}
