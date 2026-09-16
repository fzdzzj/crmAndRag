package com.slz.crm.unit.knowledge.retrieval;

import com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多条件查询拆句器单测（fix-multicondition-recall 任务 1.2）。
 */
class ConstraintQuerySplitterTest {

    @Test
    void t14ShapeShouldSplitIntoThreeRoutes() {
        String query = "客户主体变更后重新签合同要满足什么条件";
        List<String> routes = ConstraintQuerySplitter.split(query);

        assertEquals(3, routes.size());
        assertEquals(query, routes.get(0));
        assertEquals("客户主体变更", routes.get(1));
        assertEquals("重新签合同要满足什么条件", routes.get(2));
    }

    @Test
    void shortSideShouldNotSplit() {
        // 左仅 3 个汉字，不够格
        assertEquals(List.of("通过后重新签合同要满足什么条件"),
                ConstraintQuerySplitter.split("通过后重新签合同要满足什么条件"));
        // 右仅 2 个汉字
        assertEquals(List.of("客户主体变更后重签"),
                ConstraintQuerySplitter.split("客户主体变更后重签"));
    }

    @Test
    void noSeparatorShouldKeepOriginalOnly() {
        String query = "客户主体变更重新签合同要满足什么条件";
        assertEquals(List.of(query), ConstraintQuerySplitter.split(query));
    }

    @Test
    void andAlsoAndMeanwhileSeparatorsShouldSplit() {
        assertEquals(
                List.of("客户主体变更并且重新签合同要满足条件", "客户主体变更", "重新签合同要满足条件"),
                ConstraintQuerySplitter.split("客户主体变更并且重新签合同要满足条件"));
        assertEquals(
                List.of("客户主体变更同时重新签合同要满足条件", "客户主体变更", "重新签合同要满足条件"),
                ConstraintQuerySplitter.split("客户主体变更同时重新签合同要满足条件"));
        // 「且」单字分隔
        assertEquals(
                List.of("客户主体变更且重新签合同要满足条件", "客户主体变更", "重新签合同要满足条件"),
                ConstraintQuerySplitter.split("客户主体变更且重新签合同要满足条件"));
    }

    @Test
    void nullOrBlankShouldReturnEmpty() {
        assertTrue(ConstraintQuerySplitter.split(null).isEmpty());
        assertTrue(ConstraintQuerySplitter.split("").isEmpty());
        assertTrue(ConstraintQuerySplitter.split("   ").isEmpty());
    }

    @Test
    void asciiWordCanQualifyWhenHanInsufficient() {
        // 左=CRM（ASCII≥2）+ 右足够汉字 → 可拆
        List<String> routes = ConstraintQuerySplitter.split("CRM后重新签合同要满足条件");
        assertEquals(3, routes.size());
        assertEquals("CRM", routes.get(1));
        assertEquals("重新签合同要满足条件", routes.get(2));
    }
}
