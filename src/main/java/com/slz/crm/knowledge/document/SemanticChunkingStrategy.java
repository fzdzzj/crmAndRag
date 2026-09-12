package com.slz.crm.knowledge.document;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义切分（提案4 任务 1.2，方案02 Semantic Chunking）：按「标题行 / 段落 / 转折词句界」
 * 聚合切片，段长受 max-chunk-size 约束；边界优先级从高到低：
 *
 * <ol>
 *   <li>markdown 标题行（{@code #{1,6}} 前缀）永远开启新切片，同时开启新逻辑段（父块边界）；</li>
 *   <li>段落（连续非空行）为整装单元：装得下就并入当前切片，装不下则当前切片封口；</li>
 *   <li>超过上限的单元按句界（。！？；换行）二次切分；单句仍超上限时按上限硬切，
 *       硬切窗口后半段出现转折词则提前到词前切——切点尽量落在语义边界上。</li>
 * </ol>
 *
 * <p>父块（逻辑段，方案03 Small-to-Big）：同一标题（含首个标题前的隐式段）下的连续内容为
 * 一个逻辑段；被切成 ≥2 个切片的逻辑段，其切片共享逻辑段全文作为 {@code parentText}
 * （供摄取侧生成父块记录）；恰好 1 个切片时 {@code parentText=null}（切片自身即父块）。
 * 逻辑段从不跨页——跨页聚合会破坏 D15 页级锚点语义，页锚点由 {@link DocumentService} 按页附加。</p>
 *
 * <p>切分是纯本地确定性计算：同输入永远产出同切片序列（reingest 幂等的前提）。</p>
 */
public final class SemanticChunkingStrategy implements ChunkingStrategy {

    /** 转折/递进词表：超长句硬切窗口的次优切点（窗口后半段出现时提前到词前切）。 */
    private static final String[] TRANSITION_WORDS = {
            "然而", "但是", "不过", "因此", "所以", "同时", "此外", "另外",
            "其次", "总之", "综上", "反之", "例如", "其中", "如果", "由于"};

    /** 句界标点（含换行）：二次切分的优先切点。 */
    private static final String SENTENCE_ENDS = "。！？!?；;\n";

    private final int maxChunkSize;

    public SemanticChunkingStrategy(int maxChunkSize) {
        if (maxChunkSize < 1) {
            throw new IllegalArgumentException("max-chunk-size 必须 >= 1: " + maxChunkSize);
        }
        this.maxChunkSize = maxChunkSize;
    }

    /** 段落/标题单元：段落为连续非空行聚合，标题行（#{1,6} 前缀）单独成单元。 */
    private record Unit(String text, boolean heading) {
    }

    /** 逻辑段（父块）聚合器：累计段全文与落入该段的切片数。 */
    private static final class Section {
        private final StringBuilder text = new StringBuilder();
        private int chunkCount;
    }

    /** 待装配切片：正文 + 所属逻辑段下标。 */
    private record Pending(String text, int sectionIndex) {
    }

    @Override
    public List<StrategyChunk> split(String normalizedPageText) {
        List<Section> sections = new ArrayList<>();
        sections.add(new Section());
        List<Pending> pendings = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int sectionIndex = 0;

        for (Unit unit : units(normalizedPageText)) {
            if (unit.heading()) {
                flush(chunk, pendings, sections, sectionIndex);
                sections.add(new Section());
                sectionIndex = sections.size() - 1;
            }
            pack(chunk, unit.text(), pendings, sections, sectionIndex);
        }
        flush(chunk, pendings, sections, sectionIndex);

        List<StrategyChunk> result = new ArrayList<>(pendings.size());
        for (Pending pending : pendings) {
            Section section = sections.get(pending.sectionIndex());
            // 单切片逻辑段无独立父块（切片自身即父块）；≥2 切片共享逻辑段全文
            result.add(new StrategyChunk(pending.text(),
                    section.chunkCount >= 2 ? section.text.toString() : null));
        }
        return result;
    }

    /** 把单元装入当前切片：装得下并入；装不下先封口；超上限单元句界二次切分后逐段再装。 */
    private void pack(StringBuilder chunk, String text,
                      List<Pending> pendings, List<Section> sections, int sectionIndex) {
        if (text.length() <= maxChunkSize) {
            if (chunk.length() > 0 && chunk.length() + 1 + text.length() > maxChunkSize) {
                flush(chunk, pendings, sections, sectionIndex);
            }
            appendUnit(chunk, text, sections.get(sectionIndex));
            return;
        }
        for (String piece : splitOversized(text)) {
            // piece 必然 <= 上限，走普通装配；同段切片在满载时依次封口
            pack(chunk, piece, pendings, sections, sectionIndex);
        }
    }

    private void appendUnit(StringBuilder chunk, String text, Section section) {
        if (chunk.length() > 0) {
            chunk.append('\n');
        }
        chunk.append(text);
        if (section.text.length() > 0) {
            section.text.append('\n');
        }
        section.text.append(text);
    }

    private void flush(StringBuilder chunk, List<Pending> pendings,
                       List<Section> sections, int sectionIndex) {
        if (chunk.length() == 0) {
            return;
        }
        pendings.add(new Pending(chunk.toString(), sectionIndex));
        sections.get(sectionIndex).chunkCount++;
        chunk.setLength(0);
    }

    /** 拆段落单元：连续非空行聚合为一段；markdown 标题行单独成单元。 */
    private List<Unit> units(String normalizedPageText) {
        List<Unit> units = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : normalizedPageText.split("\n", -1)) {
            if (isHeading(line)) {
                if (paragraph.length() > 0) {
                    units.add(new Unit(paragraph.toString(), false));
                    paragraph.setLength(0);
                }
                units.add(new Unit(line, true));
                continue;
            }
            if (line.isBlank()) {
                if (paragraph.length() > 0) {
                    units.add(new Unit(paragraph.toString(), false));
                    paragraph.setLength(0);
                }
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }
        if (paragraph.length() > 0) {
            units.add(new Unit(paragraph.toString(), false));
        }
        return units;
    }

    /** markdown 标题行：1~6 个 # 开头后接空白或行尾。 */
    private boolean isHeading(String line) {
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#') {
            hashes++;
        }
        return hashes >= 1 && hashes <= 6
                && (hashes == line.length() || Character.isWhitespace(line.charAt(hashes)));
    }

    /** 超长单元二次切分：按句界聚到上限；单句超上限再按上限硬切（转折词优先）。 */
    private List<String> splitOversized(String text) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String sentence : splitSentences(text)) {
            if (sentence.length() > maxChunkSize) {
                if (piece.length() > 0) {
                    pieces.add(piece.toString());
                    piece.setLength(0);
                }
                hardCut(sentence, pieces);
            } else if (piece.length() + sentence.length() > maxChunkSize) {
                pieces.add(piece.toString());
                piece.setLength(0);
                piece.append(sentence);
            } else {
                piece.append(sentence);
            }
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    /** 按句末标点/换行切句，分隔符归前句（保证拼回等于原文，不丢字）。 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder sentence = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            sentence.append(current);
            if (SENTENCE_ENDS.indexOf(current) >= 0) {
                sentences.add(sentence.toString());
                sentence.setLength(0);
            }
        }
        if (sentence.length() > 0) {
            sentences.add(sentence.toString());
        }
        return sentences;
    }

    /** 单句硬切：每段 <= 上限；窗口后半段出现转折词时提前到词前切（转折词归下一段开头）。 */
    private void hardCut(String sentence, List<String> pieces) {
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + maxChunkSize, sentence.length());
            int cut = end;
            if (end < sentence.length()) {
                int half = start + maxChunkSize / 2;
                int best = -1;
                for (String word : TRANSITION_WORDS) {
                    int at = sentence.indexOf(word, half);
                    while (at >= 0 && at < end) {
                        if (at > best) {
                            best = at;
                        }
                        at = sentence.indexOf(word, at + 1);
                    }
                }
                if (best > half) {
                    cut = best;
                }
            }
            pieces.add(sentence.substring(start, cut));
            start = cut;
        }
    }
}
