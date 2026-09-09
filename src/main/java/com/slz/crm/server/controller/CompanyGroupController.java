package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.CompanyGroupDTO;
import com.slz.crm.pojo.entity.CompanyGroupEntity;
import com.slz.crm.server.service.CompanyGroupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集团主数据
 *
 * @author CRM Team
 */
@RestController
@RequestMapping("/company-group")
@Slf4j
public class CompanyGroupController {

    @Autowired
    private CompanyGroupService companyGroupService;

    /**
     * 查询集团列表（客户表单下拉/搜索用）
     *
     * @param keyword 集团名称模糊关键字（可空）
     * @param status  状态过滤（可空）
     * @return 集团列表
     */
    @GetMapping("/list")
    @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
    public Result<List<CompanyGroupEntity>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer status) {
        return Result.success(companyGroupService.list(keyword, status));
    }

    /**
     * 新增集团（客户表单输入新集团回车时调用）
     *
     * @param dto 集团 DTO
     * @return 创建后的集团
     */
    @PostMapping
    @RequirePermission(PermissionOperates.CUSTOMER_ADD_COMPANY)
    public Result<CompanyGroupEntity> add(@RequestBody CompanyGroupDTO dto) {
        return Result.success(companyGroupService.add(dto));
    }

    /**
     * 编辑集团（组织架构管理）
     *
     * @param dto 集团 DTO（需携带 id）
     * @return 更新后的集团
     */
    @PutMapping
    @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ORG)
    public Result<CompanyGroupEntity> update(@RequestBody CompanyGroupDTO dto) {
        return Result.success(companyGroupService.update(dto));
    }

    /**
     * 启用/停用集团（组织架构管理）
     *
     * @param id     集团ID
     * @param status 1-启用，0-停用
     * @return 是否成功
     */
    @PutMapping("/{id}/status")
    @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ORG)
    public Result<Boolean> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        companyGroupService.updateStatus(id, status);
        return Result.success(true);
    }

    /**
     * 删除集团（组织架构管理；集团下存在部门时禁止删除）
     *
     * @param id 集团ID
     * @return 是否成功
     */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ORG)
    public Result<Boolean> delete(@PathVariable Long id) {
        companyGroupService.deleteById(id);
        return Result.success(true);
    }
}
