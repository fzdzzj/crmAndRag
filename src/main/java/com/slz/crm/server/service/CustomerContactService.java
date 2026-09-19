package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.CustomerContactDTO;
import com.slz.crm.pojo.dto.CustomerContactRemarkDTO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.GroupResultVO;
import com.slz.crm.pojo.vo.UserVO;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface CustomerContactService {

  /**
   * 通过EXCEl添加数据
   *
   * @param file
   * @return 成功添加的数量
   */
  int list(MultipartFile file);

  /**
   * 批量更新数据
   *
   * @param list
   * @return 成功更新的数量
   */
  int updateList(List<CustomerContactDTO> list);

  /**
   * 新增数据
   *
   * @param customerContactDTO
   * @return 成功新增的数量
   */
  CustomerContactVO insert(CustomerContactDTO customerContactDTO);

  /**
   * 批量删除或恢复
   *
   * @param idList
   * @param isDelete
   * @return 成功删除或恢复的数量
   */
  int deleteOrRecoverByIds(List<Long> idList, boolean isDelete);

  /**
   * 彻底删除联系人（物理删除）
   *
   * @param idList 联系人ID列表
   * @return 成功删除数量
   */
  int deleteByIds(List<Long> idList);

  /**
   * 导出EXCEL
   *
   * @return
   */
  byte[] getExcel();

  /**
   * 分页查询
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 联系人列表
   */
  Page<CustomerContactVO> getCustomerContactByCompanyId(Integer pageNum, Integer pageSize);

  /**
   * 组合查询
   *
   * @param customerContactDTO
   * @return 联系人列表
   */
  Page<CustomerContactVO> search(
      CustomerContactDTO customerContactDTO, Integer pageNum, Integer pageSize);

  /**
   * 根据ID查询
   *
   * @param id
   * @return
   */
  CustomerContactVO get(Long id);

  /**
   * 按照指定字段分组查询联系人
   *
   * @param field 分组字段
   * @return 分组结果VO
   */
  GroupResultVO groupByCustomerContact(String field);

  /**
   * 导出联系人模板
   *
   * @return 导出的Excel文件字节数组
   */
  byte[] template();

  /**
   * 校验联系人备注类型
   *
   * @param remark
   */
  public void validateRemarkType(CustomerContactRemarkDTO remark);

  /**
   * 根据公司ID查询联系人列表
   *
   * @param id 公司ID
   * @return 联系人列表
   */
  List<CustomerContactVO> getContactByCompanyId(Long id);

  /**
   * 根据公司ID级联删除联系人
   *
   * @param companyId 公司ID
   */
  void cascadeDeleteByCompanyId(Long companyId);

  /**
   * 逻辑删除联系人
   *
   * @param id 联系人ID
   * @return 删除数量
   */
  Integer logicalDeleteById(Long id);

  /**
   * 批量逻辑删除联系人
   *
   * @param idList 联系人ID列表
   * @return 删除数量
   */
  Integer batchLogicalDeleteByIds(List<Long> idList);

  /**
   * 查询审核员列表
   *
   * @return
   */
  List<UserVO> getAuditor();

  /**
   * 获取联系人生日提醒消息（格式化文本）
   *
   * @param contactId 联系人 ID
   * @return 生日提醒消息列表
   */
  List<String> getBirthdayReminderMessage(Long contactId);
}
