package com.slz.crm.server.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.CompanyDeptDTO;
import com.slz.crm.pojo.entity.CompanyDeptEntity;
import com.slz.crm.server.service.CompanyDeptService;
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
 * 部门主数据
 *
 * @author CRM Team
 */
@RestController
@RequestMapping("/company-dept")
@Slf4j
public class CompanyDeptController {

    @Autowired
    private CompanyDeptService companyDeptService;

    /**
     * 查询部门列表（按集团加载下拉选项用）
     *
     * @param groupId 所属集团ID（可空）
     * @param keyword 部门名称模糊关键字（可空）
     * @param status  状态过滤（可空）
     * @return 部门列表
     */
    @GetMapping("/list")
    @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
    public Result<List<CompanyDeptEntity>> list(@RequestParam(required = false) Long groupId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status) {
        return Result.success(companyDeptService.list(groupId, keyword, status));
    }

    /**
     * 新增部门（客户表单输入新部门回车时调用，自动归属到所选集团下）
     *
     * @param dto 部门 DTO
     * @return 创建后的部门
     */
    @PostMapping
    @RequirePermission(PermissionOperates.CUSTOMER_ADD_COMPANY)
    public Result<CompanyDeptEntity> add(@RequestBody CompanyDeptDTO dto) {
        return Result.success(companyDeptService.add(dto));
    }

    /**
     * 编辑部门（组织架构管理）
     *
     * @param dto 部门 DTO（需携带 id）
     * @return 更新后的部门
     */
    @PutMapping
    @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ORG)
    public Result<CompanyDeptEntity> update(@RequestBody CompanyDeptDTO dto) {
        return Result.success(companyDeptService.update(dto));
    }

    /**
     * 启用/停用部门（组织架构管理）
     *
     * @param id     部门ID
     * @param status 1-启用，0-停用
     * @return 是否成功
     */
    @PutMapping("/{id}/status")
    @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ORG)
    public Result<Boolean> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        companyDeptService.updateStatus(id, status);
        return Result.success(true);
    }

    /**
     * 删除部门（组织架构管理；不影响客户历史文本数据）
     *
     * @param id 部门ID
     * @return 是否成功
     */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionOperates.SYSTEM_MANAGE_ORG)
    public Result<Boolean> delete(@PathVariable Long id) {
        companyDeptService.deleteById(id);
        return Result.success(true);
    }
}
