package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.dto.CustomerMergeDTO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.GroupResultVO;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.UserService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 客户公司 */
@RestController
@RequestMapping("/company")
@Slf4j
public class CustomerCompanyController {

  @Autowired private CustomerCompanyService customerCompanyService;

  @Autowired private CustomerContactService customerContactService;

  @Autowired private UserService userService;

  /**
   * 新增客户公司
   *
   * @param customerCompanyDTO 客户公司DTO
   * @return 是否添加成功
   */
  @PostMapping
  @RequirePermission(PermissionOperates.CUSTOMER_ADD_COMPANY)
  public Result<Boolean> add(@RequestBody CustomerCompanyDTO customerCompanyDTO) {
    return Result.success(customerCompanyService.add(customerCompanyDTO) != null);
  }

  /**
   * 新增客户公司列表
   *
   * @param file 客户公司列表文件
   * @return 成功添加的数量
   */
  @PostMapping("/list")
  @RequirePermission(PermissionOperates.CUSTOMER_ADD_COMPANY)
  public Result<Integer> list(@RequestParam("file") MultipartFile file) {
    int count = customerCompanyService.list(file);
    return Result.success(count);
  }

  /**
   * 更新客户公司列表
   *
   * @param customerCompanyDTOList 客户公司DTO列表
   * @return 成功更新的数量
   */
  @PutMapping
  @RequirePermission(PermissionOperates.CUSTOMER_UPDATE_CUSTOMER_COMPANY)
  public Result<Integer> update(@RequestBody List<CustomerCompanyDTO> customerCompanyDTOList) {
    return Result.success(customerCompanyService.updateList(customerCompanyDTOList));
  }

  /**
   * 删除客户公司列表
   *
   * @param idList 客户公司ID列表
   * @return 成功删除的数量
   */
  @DeleteMapping
  @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CUSTOMER_COMPANY)
  public Result<Integer> delete(@RequestBody List<Long> idList) {
    return Result.success(customerCompanyService.deleteByIds(idList));
  }

  /**
   * 客户公司丢入回收站
   *
   * @param id 客户公司ID
   * @return 是否删除成功
   */
  @DeleteMapping("/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CUSTOMER_COMPANY)
  public Result<Boolean> delete(@PathVariable Long id) {
    return Result.success(customerCompanyService.deleteById(id));
  }

  /**
   * 恢复客户公司列表
   *
   * @param idList 客户公司ID列表
   * @return 成功恢复的数量
   */
  @PutMapping("/recover")
  @RequirePermission(PermissionOperates.CUSTOMER_RECOVER_CUSTOMER_COMPANY)
  public Result<Integer> recover(@RequestBody List<Long> idList) {
    return Result.success(customerCompanyService.deleteOrRecoverByIds(idList, false));
  }

  /**
   * 根据联系人信息获取其所属公司信息
   *
   * @param id 客户联系人ID
   * @return 公司信息VO对象
   */
  @GetMapping("/getCompanyByCondition/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  private Result<CustomerCompanyVO> getCompanyByCondition(@PathVariable Long id) {
    return Result.success(customerCompanyService.getCompanyByCondition(id));
  }

  /**
   * 导出客户公司数据
   *
   * @return 导出的Excel文件字节数组
   */
  @GetMapping("/excel")
  @RequirePermission(PermissionOperates.CUSTOMER_EXPORT_CUSTOMER_COMPANY)
  private Result<byte[]> excel() {
    return Result.success(customerCompanyService.excel());
  }

  /**
   * 导出Excel模板
   *
   * @return 导出的Excel文件字节数组
   */
  @GetMapping("/template")
  private Result<byte[]> template() {
    return Result.success(customerCompanyService.template());
  }

  /**
   * 合并客户公司
   *
   * @param customerMergeDTO 客户合并DTO对象
   * @return 是否合并成功
   */
  @PutMapping("/merge")
  @RequirePermission(PermissionOperates.CUSTOMER_MERGE_COMPANY)
  public Result<Boolean> merge(@RequestBody CustomerMergeDTO customerMergeDTO) {
    return Result.success(customerCompanyService.merge(customerMergeDTO));
  }

  /**
   * 获取所有客户公司
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 客户公司VO列表
   */
  @GetMapping
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  private Result<Page<CustomerCompanyVO>> getAllCompany(
      @RequestParam Integer pageNum, @RequestParam Integer pageSize) {
    return Result.success(
        customerCompanyService.customQuery(new CustomerCompanyDTO(), pageNum, pageSize));
  }

  /**
   * 获取客户公司详情
   *
   * @param id 客户公司ID
   * @return 客户公司VO对象
   */
  @GetMapping("/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_VIEW_COMPANY)
  private Result<Map<String, Object>> getCompanyDetail(
      @PathVariable Long id, @RequestParam Integer pageNum, @RequestParam Integer pageSize) {
    if (pageNum == null || pageSize == null || pageNum <= 0 || pageSize <= 0) {
      pageNum = 1;
      pageSize = 10;
    }
    Map<String, Object> map = new HashMap<>();
    CustomerCompanyVO companyDetail = customerCompanyService.getCompanyDetail(id);

    map.put("company", companyDetail);
    // 查询创建人
    map.put("creator", userService.getById(companyDetail.getCreatorId()));

    // 查询负责人
    try {
      map.put("owner", userService.getById(companyDetail.getOwnerId()));
    } catch (Exception e) {
      map.put("owner", null);
    }

    // 查询联系人
    try {
      CustomerContactDTO customerContactDTO = new CustomerContactDTO();
      customerContactDTO.setCompanyId(id);

      map.put(
          "contactList",
          customerContactService.search(customerContactDTO, pageNum, pageSize).getRecords());
    } catch (Exception e) {
      map.put("contactList", null);
    }

    return Result.success(map);
  }

  /**
   * 模糊查询客户公司
   *
   * @return 客户公司VO列表
   */
  @GetMapping("/find")
  @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
  private Result<Page<CustomerCompanyVO>> findCompany(
      @RequestParam String companyName,
      @RequestParam Integer pageNum,
      @RequestParam Integer pageSize) {
    return Result.success(customerCompanyService.findCompany(companyName, pageNum, pageSize));
  }

  /**
   * 自定义组合查询
   *
   * @param customerCompanyDTO 自定义查询DTO
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 客户公司VO列表
   */
  @GetMapping("/custom")
  @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
  private Result<Page<CustomerCompanyVO>> customQuery(
      CustomerCompanyDTO customerCompanyDTO, Integer pageNum, Integer pageSize) {

    if (pageNum == null || pageSize == null || pageNum <= 0 || pageSize <= 0) {
      pageNum = 1;
      pageSize = 10;
    }

    return Result.success(
        customerCompanyService.customQuery(customerCompanyDTO, pageNum, pageSize));
  }

  /**
   * 分组查询客户公司
   *
   * @param field 分组字段
   * @return 分组结果VO对象
   */
  @GetMapping("/group")
  @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
  public Result<GroupResultVO> groupCompany(@RequestParam String field) {
    return Result.success(customerCompanyService.groupByCustomerCompany(field));
  }

  /**
   * 逻辑删除客户公司
   *
   * @param id 客户公司ID
   * @return 删除数量
   */
  @DeleteMapping("/logical/{id}")
  @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CUSTOMER_COMPANY)
  public Result<Integer> logicalDelete(@PathVariable Long id) {
    return Result.success(customerCompanyService.logicalDeleteById(id));
  }

  /**
   * 批量逻辑删除客户公司
   *
   * @param idList 客户公司ID列表
   * @return 删除数量
   */
  @DeleteMapping("/logical/batch")
  @RequirePermission(PermissionOperates.CUSTOMER_DELETE_CUSTOMER_COMPANY)
  public Result<Integer> batchLogicalDelete(@RequestBody List<Long> idList) {
    return Result.success(customerCompanyService.batchLogicalDeleteByIds(idList));
  }

  /**
   * 通过归属集团模糊查询客户公司
   *
   * @param belongGroup 集团名称
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 客户公司VO列表
   */
  @GetMapping("/group/find")
  @RequirePermission(PermissionOperates.CUSTOMER_QUERY_COMPANY)
  public Result<Page<CustomerCompanyVO>> findCompanyByGroup(
      @RequestParam String belongGroup,
      @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
      @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
    if (belongGroup == null || belongGroup.trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "归属集团不能为空");
    }
    return Result.success(
        customerCompanyService.findCompanyByGroup(belongGroup, pageNum, pageSize));
  }
}
