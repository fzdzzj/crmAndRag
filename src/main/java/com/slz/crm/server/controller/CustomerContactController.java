package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.GroupResultVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.service.CustomerContactService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 客户联系人
 */
@RestController
@RequestMapping("/contact")
@Slf4j
public class CustomerContactController {

    @Autowired
    private CustomerContactService customerContactService;

    /**
     * 通过 EXCEl 添加数据
     *
     * @param file 上传的 Excel 文件
     * @return 成功添加的数量
     */
    @PostMapping("/list")
    @RequirePermission(PermissionOperates.CUSTOMER_EXCEL_ADD)
    public Result<Integer> list(@RequestParam("file") MultipartFile file) {
        int count = customerContactService.list(file);
        return Result.success(count);
    }

    /**
     * 批量更新数据
     *
     * @param list 联系人 DTO 列表
     * @return 成功更新的数量
     */
    @PutMapping
    @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CONTACT)
    private Result<Integer> update(@RequestBody List<CustomerContactDTO> list) {
        int count = customerContactService.updateList(list);
        return Result.success(count);
    }

    /**
     * 新增数据
     *
     * @param customerContactDTO 联系人 DTO
     * @return 成功新增的数量
     */
    @PostMapping
    @RequirePermission(PermissionOperates.CUSTOMER_ADD_CONTACT)
    private Result<Integer> insert(@RequestBody CustomerContactDTO customerContactDTO) {
        return Result.success(customerContactService.insert(customerContactDTO) != null ? 1 : 0);
    }

    /**
     * 批量删除数据
     *
     * @param idList 联系人 ID 列表
     * @return 成功删除的数量
     */
    @DeleteMapping
    @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CONTACT)
    private Result<Integer> delete(@RequestBody List<Long> idList) {
        return Result.success(customerContactService.deleteByIds(idList));
    }

    /**
     * 批量恢复数据
     *
     * @param idList 联系人 ID 列表
     * @return 成功恢复的数量
     */
    @PutMapping("/recover")
    @RequirePermission(PermissionOperates.CUSTOMER_RECOVER_CONTACT)
    private Result<Integer> recover(@RequestBody List<Long> idList) {
        int count = customerContactService.deleteOrRecoverByIds(idList, false);
        return Result.success(count);
    }

    /**
     * 根据公司 ID 查询联系人列表
     *
     * @param customerCompanyDTO 公司 DTO
     * @return 联系人列表
     */
    @GetMapping("/getContactByCompanyId")
    @RequirePermission(PermissionOperates.CUSTOMER_QUERY_CONTACT)
    private Result<List<CustomerContactVO>> getContactByCompanyId(CustomerCompanyDTO customerCompanyDTO) {

        List<CustomerContactVO> customerContactVOList = customerContactService.getContactByCompanyId(customerCompanyDTO.getId());
        return Result.success(customerContactVOList);
    }

    /**
     * 导出联系人列表
     *
     * @return 导出的 Excel 文件字节数组
     */
    @GetMapping("/excel")
    @RequirePermission(PermissionOperates.CUSTOMER_EXPORT_CONTACT)
    private Result<byte[]> getExcel() {
        byte[] bytes = customerContactService.getExcel();
        return Result.success(bytes);
    }


    /**
     * 分组查询联系人
     *
     * @param field 分组字段
     * @return 分组结果 VO
     */
    @GetMapping("/group")
    @RequirePermission(PermissionOperates.CUSTOMER_QUERY_CONTACT)
    public Result<GroupResultVO> groupContact(@RequestParam String field) {
        return Result.success(customerContactService.groupByCustomerContact(field));
    }

    /**
     * 分页查询
     *
     * @return 联系人 VO 列表
     */
    @GetMapping
    @RequirePermission(PermissionOperates.CUSTOMER_QUERY_CONTACT)
    private Result<Page<CustomerContactVO>> getCustomerContactByCompanyId(@RequestParam Integer pageNum, @RequestParam Integer pageSize) {
        if (pageNum == null || pageSize == null || pageNum <= 0 || pageSize <= 0) {
            pageNum = 1;
            pageSize = 10;
        }
        Page<CustomerContactVO> customerContactVOList = customerContactService.search(new CustomerContactDTO(),pageNum, pageSize);
        return Result.success(customerContactVOList);
    }

    /**
     * 组合查询
     *
     * @param customerContactDTO 联系人 DTO
     * @return 联系人 VO 列表
     */
    @GetMapping("/search")
    @RequirePermission(PermissionOperates.CUSTOMER_QUERY_CONTACT)
    private Result<Page<CustomerContactVO>> search(CustomerContactDTO customerContactDTO, Integer pageNum, Integer pageSize) {

        if (pageNum == null || pageSize == null || pageNum <= 0 || pageSize <= 0) {
            pageNum = 1;
            pageSize = 10;
        }
        Page<CustomerContactVO> customerContactVOList = customerContactService.search(customerContactDTO, pageNum, pageSize);
        return Result.success(customerContactVOList);
    }

    /**
     * 导出联系人模板
     *
     * @return 导出的 Excel 文件字节数组
     */
    @GetMapping("/template")
    private Result<byte[]> template() {
        byte[] bytes = customerContactService.template();
        return Result.success(bytes);
    }



    /**
     * 逻辑删除联系人
     *
     * @param id 联系人 ID
     * @return 删除数量
     */
    @DeleteMapping("/logical/{id}")
    @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CONTACT)
    public Result<Integer> logicalDelete(@PathVariable Long id) {
        return Result.success(customerContactService.logicalDeleteById(id));
    }

    /**
     * 批量逻辑删除联系人
     *
     * @param idList 联系人 ID 列表
     * @return 删除数量
     */
    @DeleteMapping("/logical/batch")
    @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CONTACT)
    public Result<Integer> batchLogicalDelete(@RequestBody List<Long> idList) {
        return Result.success(customerContactService.batchLogicalDeleteByIds(idList));
    }


    /**
     * 查询审核员列表
     *
     * @return
     */
    @GetMapping("/auditor")
    public Result<List<UserVO>> auditor() {
        return Result.success(customerContactService.getAuditor());
    }

    /**
     * 获取联系人生日提醒消息
     *
     * @param id 联系人 ID
     * @return 生日提醒消息列表
     */
    @GetMapping("/birthday-message/{id}")
    @RequirePermission(PermissionOperates.CUSTOMER_QUERY_CONTACT)
    public Result<List<String>> getBirthdayReminderMessage(@PathVariable Long id) {
        List<String> messages = customerContactService.getBirthdayReminderMessage(id);
        return Result.success(messages);
    }

}
