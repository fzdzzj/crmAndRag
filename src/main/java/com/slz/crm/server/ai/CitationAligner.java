package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.SourceReference;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成后引用编号与片段对齐器（零外呼、确定性）。
 *
 * <p>fix-citation-alignment 任务 1.1：在落库/对外 citations/评测抽取之前，
 * 用 CJK 二字子句覆盖率 + ASCII/数字 token 加成校验每个 {@code [n]} 与
 * {@link SourceReference} 列表的支撑关系，按 KEEP / REMAP / DROP 改写编号。</p>
 *
 * <p>不调用模型、无 Spring 依赖、不补漏引、不编造来源。</p>
 */
public final class CitationAligner {

    /** 原编号可保留的最低支撑分。 */
    public static final double KEEP_MIN = 0.12;

    /** 重映射到最佳片段所需的最低支撑分。 */
    public static final double REMAP_MIN = 0.22;

    /** 平局带宽：得分差小于此值时保留原编号，避免误伤。 */
    public static final double TIE_MARGIN = 0.08;

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z0-9]+");
    private static final double TOKEN_BONUS_WEIGHT = 0.25;

    private CitationAligner() {
    }

    /**
     * 对齐答案中的引用编号。
     *
     * @param answer  模型生成原文（可含 {@code [n]}）
     * @param sources 检索命中列表（1-based 编号对应下标 0）
     * @return 对齐后文本与去重后的 citations（按出现顺序）
     */
    public static Alignment align(String answer, List<SourceReference> sources) {
        if (answer == null || answer.isBlank()) {
            return new Alignment(answer == null ? "" : answer, List.of());
        }
        if (sources == null || sources.isEmpty()) {
            return new Alignment(answer, List.of());
        }

        Matcher matcher = CITATION_PATTERN.matcher(answer);
        if (!matcher.find()) {
            return new Alignment(answer, List.of());
        }

        StringBuilder out = new StringBuilder(answer.length());
        LinkedHashSet<Integer> citations = new LinkedHashSet<>();
        int last = 0;
        matcher.reset();
        while (matcher.find()) {
            out.append(answer, last, matcher.start());
            int original = Integer.parseInt(matcher.group(1));
            String clause = clauseAround(answer, matcher.start(), matcher.end());
            int decided = decide(original, clause, sources);
            if (decided > 0) {
                out.append('[').append(decided).append(']');
                citations.add(decided);
            }
            last = matcher.end();
        }
        out.append(answer, last, answer.length());
        return new Alignment(out.toString(), List.copyOf(citations));
    }

    /**
     * 对单个 {@code [n]} 做 KEEP / REMAP / DROP 决策。
     *
     * @return 保留或重映射后的 1-based 编号；0 表示 DROP
     */
    private static int decide(int original, String clause, List<SourceReference> sources) {
        int n = sources.size();
        double[] scores = new double[n];
        int bestIdx = -1;
        double bestScore = -1.0d;
        for (int i = 0; i < n; i++) {
            String excerpt = sources.get(i) == null ? null : sources.get(i).excerpt();
            scores[i] = supportScore(clause, excerpt);
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestIdx = i;
            }
        }

        boolean inRange = original >= 1 && original <= n;
        double scoreOriginal = inRange ? scores[original - 1] : -1.0d;

        // KEEP：原编号在范围内、自身够强、且不显著弱于最佳
        if (inRange && scoreOriginal >= KEEP_MIN && scoreOriginal + TIE_MARGIN >= bestScore) {
            return original;
        }

        // REMAP：存在足够强的最佳片段，且原编号越界/过弱/显著落后
        if (bestIdx >= 0 && bestScore >= REMAP_MIN
                && (!inRange || scoreOriginal < KEEP_MIN || bestScore - scoreOriginal >= TIE_MARGIN)) {
            return bestIdx + 1;
        }

        // DROP：无可靠支撑
        return 0;
    }

    /**
     * 取引用编号所在子句（按 。！？；\n 切分；含编号前与编号后至下一分隔/下一引用）。
     * 中置编号如「依据[2]结论」会得到「依据结论」，避免只取前缀导致二字 bigram 为空。
     */
    static String clauseAround(String answer, int citationStart, int citationEnd) {
        int leftEnd = citationStart;
        while (leftEnd > 0 && Character.isWhitespace(answer.charAt(leftEnd - 1))) {
            leftEnd--;
        }
        int start = leftEnd;
        while (start > 0) {
            char c = answer.charAt(start - 1);
            if (isClauseDelimiter(c)) {
                break;
            }
            if (c == ']' && looksLikeCitationClose(answer, start - 1)) {
                break;
            }
            start--;
        }

        int end = citationEnd;
        while (end < answer.length() && Character.isWhitespace(answer.charAt(end))) {
            end++;
        }
        int right = end;
        while (right < answer.length()) {
            char c = answer.charAt(right);
            if (isClauseDelimiter(c)) {
                break;
            }
            // 下一处引用 [n] 起边界
            if (c == '[' && looksLikeCitationOpen(answer, right)) {
                break;
            }
            right++;
        }

        String left = answer.substring(start, leftEnd).trim();
        String rightPart = answer.substring(end, right).trim();
        if (left.isEmpty()) {
            return rightPart;
        }
        if (rightPart.isEmpty()) {
            return left;
        }
        return left + rightPart;
    }

    private static boolean looksLikeCitationOpen(String answer, int openIdx) {
        if (openIdx + 1 >= answer.length() || !Character.isDigit(answer.charAt(openIdx + 1))) {
            return false;
        }
        int i = openIdx + 1;
        int digits = 0;
        while (i < answer.length() && Character.isDigit(answer.charAt(i)) && digits < 3) {
            i++;
            digits++;
        }
        return digits > 0 && i < answer.length() && answer.charAt(i) == ']';
    }
private static boolean looksLikeCitationClose(String answer, int closeIdx) {
        // 形如 [12] 的 ] 
        int i = closeIdx - 1;
        while (i >= 0 && Character.isDigit(answer.charAt(i))) {
            i--;
        }
        return i >= 0 && answer.charAt(i) == '[' && closeIdx - i <= 4;
    }

    private static boolean isClauseDelimiter(char c) {
        // 。！？； and ASCII ! ? ; newline
        return c == '\u3002' || c == '\uff01' || c == '\uff1f' || c == '\uff1b'
                || c == '\n' || c == '!' || c == '?' || c == ';';
    }

    /**
     * 支撑分：CJK 二字子句覆盖率 + 共享 ASCII/数字 token 加成，夹到 [0,1]。
     */
    static double supportScore(String clause, String excerpt) {
        if (clause == null || clause.isBlank() || excerpt == null || excerpt.isBlank()) {
            return 0.0d;
        }
        // 主信号：子句 bigram 被 excerpt 覆盖的比例（避免长 excerpt 稀释 Jaccard）
        double coverage = clauseCoverage(cjkBigrams(clause), cjkBigrams(excerpt));
        double tokenBonus = tokenOverlapBonus(clause, excerpt);
        return Math.min(1.0d, coverage + tokenBonus);
    }

    private static double tokenOverlapBonus(String clause, String excerpt) {
        Set<String> a = asciiTokens(clause);
        Set<String> b = asciiTokens(excerpt);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        int shared = 0;
        for (String t : a) {
            if (b.contains(t)) {
                shared++;
            }
        }
        if (shared == 0) {
            return 0.0d;
        }
        return TOKEN_BONUS_WEIGHT * ((double) shared / a.size());
    }

    private static double clauseCoverage(Set<String> clauseGrams, Set<String> excerptGrams) {
        if (clauseGrams.isEmpty()) {
            return 0.0d;
        }
        int hit = 0;
        for (String g : clauseGrams) {
            if (excerptGrams.contains(g)) {
                hit++;
            }
        }
        return (double) hit / (double) clauseGrams.size();
    }

    static Set<String> cjkBigrams(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.length() < 2) {
            return out;
        }
        for (int i = 0; i < text.length() - 1; i++) {
            char a = text.charAt(i);
            char b = text.charAt(i + 1);
            if (isCjk(a) && isCjk(b)) {
                out.add(new String(new char[]{a, b}));
            }
        }
        return out;
    }

    static Set<String> asciiTokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher matcher = ASCII_TOKEN.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }

    /**
     * 对齐结果。
     *
     * @param text      对齐后的答案文本
     * @param citations 保留/重映射后的 1-based 引用编号（出现序、去重）
     */
    public record Alignment(String text, List<Integer> citations) {
        public Alignment {
            text = Objects.requireNonNullElse(text, "");
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}
