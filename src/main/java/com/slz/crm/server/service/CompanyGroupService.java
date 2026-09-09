package com.slz.crm.server.service;

import com.slz.crm.pojo.dto.CompanyGroupDTO;
import com.slz.crm.pojo.entity.CompanyGroupEntity;

import java.util.List;

/**
 * 集团主数据服务
 *
 * @author CRM Team
 */
public interface CompanyGroupService {

    /**
     * 查询集团列表
     *
     * @param keyword 集团名称模糊关键字（可空）
     * @param status  状态过滤（可空；1-启用，0-停用）
     * @return 集团列表
     */
    List<CompanyGroupEntity> list(String keyword, Integer status);

    /**
     * 新增集团（名称去重）
     *
     * @param dto 集团 DTO
     * @return 创建后的集团（含ID）
     */
    CompanyGroupEntity add(CompanyGroupDTO dto);

    /**
     * 编辑集团（名称查重，排除自身）
     *
     * @param dto 集团 DTO（需携带 id）
     * @return 更新后的集团
     */
    CompanyGroupEntity update(CompanyGroupDTO dto);

    /**
     * 启用/停用集团
     *
     * @param id     集团ID
     * @param status 1-启用，0-停用
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除集团（集团下存在部门时禁止删除）
     *
     * @param id 集团ID
     */
    void deleteById(Long id);
}
