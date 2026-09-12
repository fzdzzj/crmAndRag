package com.slz.crm.knowledge.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定滑窗切分（提案4 任务 1.1）：升级前 {@code DocumentService} 内联算法的逐字等价搬运。
 *
 * <p>CHUNK_SIZE=320 / CHUNK_OVERLAP=40 为冻结等价参数——改这两个值会破坏
 * "fixed 策略与升级前切分行为一致" 的规范承诺（document-chunking：固定策略回退场景）。</p>
 */
public final class FixedChunkingStrategy implements ChunkingStrategy {

    /** 升级前硬编码的切片尺寸。 */
    static final int CHUNK_SIZE = 320;
    /** 升级前硬编码的切片重叠。 */
    static final int CHUNK_OVERLAP = 40;

    static final FixedChunkingStrategy INSTANCE = new FixedChunkingStrategy();

    @Override
    public List<StrategyChunk> split(String normalizedPageText) {
        List<StrategyChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalizedPageText.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalizedPageText.length());
            chunks.add(new StrategyChunk(normalizedPageText.substring(start, end), null));
            if (end >= normalizedPageText.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }
}
