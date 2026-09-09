package com.slz.crm.unit.ai;

import com.slz.crm.server.ai.AiThinkTagStripper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 思考内容剥离与正文回灌保护验证。 */
class AiThinkTagStripperTest {

    @Test
    void strip_removesCompleteAndUnclosedBlocks() {
        assertThat(AiThinkTagStripper.strip("<think>推理</think>回答")).isEqualTo("回答");
        assertThat(AiThinkTagStripper.strip("回答<think:abc>推理")).isEqualTo("回答");
    }

    @Test
    void streaming_removesTagAcrossChunks() {
        AiThinkTagStripper.StreamingStripper stripper = AiThinkTagStripper.streaming();

        assertThat(stripper.filter("<thi")).isEmpty();
        assertThat(stripper.filter("nk>推理</thi")).isEmpty();
        assertThat(stripper.filter("nk>答案")).isEqualTo("答案");
        assertThat(stripper.flush()).isEmpty();
    }

    @Test
    void streaming_emitsThinkingOnlyWhenSinkProvided() {
        AiThinkTagStripper.StreamingStripper stripper = AiThinkTagStripper.streaming();
        List<String> thinking = new ArrayList<>();

        assertThat(stripper.filter("<think>推", thinking::add)).isEmpty();
        assertThat(stripper.filter("理</think>答案", thinking::add)).isEqualTo("答案");

        assertThat(String.join("", thinking)).isEqualTo("推理");
    }
}
