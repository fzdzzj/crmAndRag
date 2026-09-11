package com.slz.crm.server.ai;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 模型输出中的 think 块剥离器。
 *
 * <p>思考过程只允许通过独立的 {@code thinking} SSE 事件透传；会话历史、消息正文和回灌
 * prompt 一律使用剥离后的文本，避免推理内容逐轮膨胀。</p>
 */
public final class AiThinkTagStripper {

    private static final String OPEN_PREFIX = "<think";
    private static final String CLOSE_PREFIX = "</think";
    private static final int OPEN_CANDIDATE_MIN_LENGTH = OPEN_PREFIX.length();

    private static final Pattern COMPLETE_BLOCK = Pattern.compile(
            "<think[^>]*>.*?</think[^>]*>", Pattern.DOTALL);
    private static final Pattern UNCLOSED_BLOCK = Pattern.compile("<think[^>]*>?.*", Pattern.DOTALL);
    private static final Pattern ORPHAN_CLOSE = Pattern.compile("</think[^>]*>");

    private AiThinkTagStripper() {
    }

    /**
     * 整串剥离推理块，用于同步返回值、标题和已落库历史的兜底清洗。
     *
     * <p>未闭合的 {@code <think>} 会丢弃其后全部内容；宁可误删也不让推理内容回灌。</p>
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = COMPLETE_BLOCK.matcher(text).replaceAll("");
        result = UNCLOSED_BLOCK.matcher(result).replaceAll("");
        result = ORPHAN_CLOSE.matcher(result).replaceAll("");
        return result;
    }

    /**
     * 创建流式剥离器；每个生成必须持有独立实例，不能跨请求复用。
     */
    public static StreamingStripper streaming() {
        return new StreamingStripper();
    }

    /**
     * 支持标签跨分片到达的流式状态机。
     */
    public static final class StreamingStripper {
        private enum State {
            NORMAL,
            IN_THINK
        }

        private State state = State.NORMAL;
        private final StringBuilder openPending = new StringBuilder();
        private final StringBuilder closePending = new StringBuilder();
        private final StringBuilder thinkPending = new StringBuilder();

        /**
         * 处理增量并返回放行到正文的文本。
         */
        public String filter(String delta) {
            return filter(delta, null);
        }

        /**
         * 处理增量；thinkingSink 接收 think 块内的片段，返回值只包含正文。
         */
        public String filter(String delta, Consumer<String> thinkingSink) {
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            StringBuilder output = new StringBuilder();
            for (int index = 0; index < delta.length(); index++) {
                char character = delta.charAt(index);
                if (state == State.IN_THINK) {
                    processInThink(character, thinkingSink);
                    continue;
                }
                processNormal(character, output);
            }
            flushThinking(thinkingSink);
            return output.toString();
        }

        /**
         * 流结束时冲刷缓冲；未闭合的 think 块整体丢弃。
         */
        public String flush() {
            String tail = openPending.toString();
            openPending.setLength(0);
            closePending.setLength(0);
            if (state == State.IN_THINK) {
                state = State.NORMAL;
                thinkPending.setLength(0);
                return "";
            }
            if (tail.length() >= OPEN_CANDIDATE_MIN_LENGTH
                    && looksLikeOpenPrefix(tail)) {
                return "";
            }
            return tail;
        }

        private void processNormal(char character, StringBuilder output) {
            if (!openPending.isEmpty()) {
                openPending.append(character);
                if (character == '>') {
                    String tag = openPending.toString();
                    openPending.setLength(0);
                    if (isThinkOpen(tag)) {
                        state = State.IN_THINK;
                    } else {
                        output.append(tag);
                    }
                } else if (!looksLikeOpenPrefix(openPending)) {
                    output.append(openPending);
                    openPending.setLength(0);
                }
                return;
            }
            if (character == '<') {
                openPending.append(character);
            } else {
                output.append(character);
            }
        }

        private void processInThink(char character, Consumer<String> thinkingSink) {
            if (closePending.isEmpty()) {
                if (character == '<') {
                    closePending.append(character);
                } else {
                    thinkPending.append(character);
                }
                return;
            }
            closePending.append(character);
            if (character == '>') {
                String tag = closePending.toString();
                closePending.setLength(0);
                if (isThinkClose(tag)) {
                    state = State.NORMAL;
                    if (thinkingSink != null && !thinkPending.isEmpty()) {
                        thinkingSink.accept(thinkPending.toString());
                    }
                    thinkPending.setLength(0);
                }
            } else if (!looksLikeClosePrefix(closePending)) {
                thinkPending.append(closePending);
                closePending.setLength(0);
            }
        }

        private void flushThinking(Consumer<String> thinkingSink) {
            if (thinkingSink == null || state != State.IN_THINK || thinkPending.isEmpty()) {
                return;
            }
            // 跨分片的 thinking 可以按片段下发；闭标签前缀继续留在 closePending 中检测。
            thinkingSink.accept(thinkPending.toString());
            thinkPending.setLength(0);
        }

        private static boolean isThinkOpen(CharSequence tag) {
            return tag.toString().startsWith(OPEN_PREFIX) && tag.toString().endsWith(">");
        }

        private static boolean isThinkClose(CharSequence tag) {
            return tag.toString().startsWith(CLOSE_PREFIX) && tag.toString().endsWith(">");
        }

        private static boolean looksLikeOpenPrefix(CharSequence pending) {
            String value = pending.toString();
            return value.startsWith("<") && !value.contains(">")
                    && (OPEN_PREFIX.startsWith(value) || value.startsWith(OPEN_PREFIX));
        }

        private static boolean looksLikeClosePrefix(CharSequence pending) {
            String value = pending.toString();
            return value.startsWith("<") && !value.contains(">")
                    && (CLOSE_PREFIX.startsWith(value) || value.startsWith(CLOSE_PREFIX));
        }
    }
}
