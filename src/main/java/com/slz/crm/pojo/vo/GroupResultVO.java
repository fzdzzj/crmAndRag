// crm/src/main/java/com/slz/crm/pojo/vo/GroupResultVO.java
package com.slz.crm.pojo.vo;

import com.slz.crm.pojo.ao.Privacy;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 分组结果VO
 * @author evi
 */
@Data
public class GroupResultVO implements Privacy {
    // 分组字段名
    private String groupField;
    // 分组结果 (分组值 -> 数据列表)
    private Map<Object, ? extends List<?>> groupData;
}