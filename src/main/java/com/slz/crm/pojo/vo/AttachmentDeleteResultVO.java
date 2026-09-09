package com.slz.crm.pojo.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量删除附件的逐项结果。
 * <p>删除是尽力而为的：无权限或不存在的附件不会阻断其它有权限的附件。</p>
 */
@Data
public class AttachmentDeleteResultVO {
    private List<Long> deletedIds = new ArrayList<>();
    private List<Long> notFoundIds = new ArrayList<>();
    private Map<Long, String> denied = new LinkedHashMap<>();
}
