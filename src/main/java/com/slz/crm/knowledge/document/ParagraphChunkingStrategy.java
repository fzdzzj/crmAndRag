package com.slz.crm.knowledge.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 段落感知切分（add-paragraph-chunking）：保持与 {@link FixedChunkingStrategy} 相同的 320 窗口 / 40 重叠，
 * 但在窗口内优先收缩到最后一处段落界（{@code \n\n}，否则 {@code \n}），避免把可放入窗口的整段图注/条款拦腰切开。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>仅当 snap 位置 ≥ {@code start + 80} 才收缩，避免空切片或切太碎；</li>
 *   <li>snap 后下一窗从边界之后起算（不跨段拼下一段）；未 snap 的超长单段内仍按 320/40 滑窗；</li>
 *   <li>无换行长文与 {@code fixed} 逐字相同。</li>
 * </ul>
 *
 * <p>默认不启用；须显式 {@code rag.chunking.strategy=paragraph}。</p>
 */
public final class ParagraphChunkingStrategy implements ChunkingStrategy {

    /** 与 {@link FixedChunkingStrategy} 冻结参数一致。 */
    static final int CHUNK_SIZE = FixedChunkingStrategy.CHUNK_SIZE;
    /** 与 {@link FixedChunkingStrategy} 冻结参数一致。 */
    static final int CHUNK_OVERLAP = FixedChunkingStrategy.CHUNK_OVERLAP;
    /** snap 最小保留长度：收缩后切片至少这么长。 */
    static final int MIN_SNAP = 80;

    static final ParagraphChunkingStrategy INSTANCE = new ParagraphChunkingStrategy();

    @Override
    public List<StrategyChunk> split(String normalizedPageText) {
        List<StrategyChunk> chunks = new ArrayList<>();
        int start = 0;
        final int length = normalizedPageText.length();
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            boolean snapped = false;
            if (end < length) {
                int snap = findLastBoundary(normalizedPageText, start, end);
                if (snap >= start + MIN_SNAP) {
                    end = snap;
                    snapped = true;
                }
            }
            chunks.add(new StrategyChunk(normalizedPageText.substring(start, end), null));
            if (end >= length) {
                break;
            }
            if (snapped) {
                // 跨过段落界，不把下一段拼进上一窗的重叠区
                start = end + boundaryLength(normalizedPageText, end);
            } else {
                start = Math.max(end - CHUNK_OVERLAP, start + 1);
            }
        }
        return chunks;
    }

    /**
     * 在 {@code [start, end)} 内找最后一处段落界起点：优先 {@code \n\n}，否则 {@code \n}；没有则 -1。
     */
    static int findLastBoundary(String text, int start, int end) {
        if (end - start >= 2) {
            int dbl = text.lastIndexOf("\n\n", end - 2);
            if (dbl >= start) {
                return dbl;
            }
        }
        if (end - start >= 1) {
            int nl = text.lastIndexOf('\n', end - 1);
            if (nl >= start) {
                return nl;
            }
        }
        return -1;
    }

    /** {@code end} 处边界宽度：{@code \n\n} → 2，否则按单 {@code \n} → 1。 */
    static int boundaryLength(String text, int end) {
        if (end + 1 < text.length() && text.charAt(end) == '\n' && text.charAt(end + 1) == '\n') {
            return 2;
        }
        return 1;
    }
}
