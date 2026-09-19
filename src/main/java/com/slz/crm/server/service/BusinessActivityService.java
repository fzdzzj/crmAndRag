package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.AddActivityContactRequestDTO;
import com.slz.crm.pojo.dto.AddActivityUserRequestDTO;
import com.slz.crm.pojo.dto.BatchDeleteActivityAssociationRequestDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityQueryDTO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import java.util.List;

public interface BusinessActivityService {

  /**
   * 自定义查询活动列表
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @param dto 查询条件DTO
   * @return 活动列表
   */
  Page<BusinessActivityVO> businessActivityQuery(
      Integer pageNum, Integer pageSize, BusinessActivityQueryDTO dto);

  /**
   * 获取所有活动
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 活动列表
   */
  List<BusinessActivityVO> getAllActivity(Integer pageNum, Integer pageSize);

  /**
   * 根据ID查询活动
   *
   * @param id 活动ID
   * @return 活动详情
   */
  BusinessActivityVO getDetailById(Long id);

  /**
   * 创建活动
   *
   * @param dto 活动DTO
   * @return 是否创建成功
   */
  Boolean create(BusinessActivityDTO dto);

  /**
   * 更新活动
   *
   * @param businessActivityDTOList 活动DTO列表
   * @return 成功更新的数量
   */
  Integer updateList(List<BusinessActivityDTO> businessActivityDTOList);

  /**
   * 删除活动
   *
   * @param idList 活动ID列表
   * @return 是否删除成功
   */
  Integer deleteByIds(List<Long> idList);

  /**
   * 向活动添加联系人
   *
   * @param activityId 活动ID
   * @param contactRequestList 联系人请求列表
   * @return 是否添加成功
   */
  Boolean addContactsToActivity(
      Long activityId, List<AddActivityContactRequestDTO> contactRequestList);

  /**
   * 向活动添加用户
   *
   * @param activityId 活动ID
   * @param userRequestList 用户请求列表
   * @return 是否添加成功
   */
  Boolean addUsersToActivity(Long activityId, List<AddActivityUserRequestDTO> userRequestList);

  /**
   * 批量删除活动的联系人和用户
   *
   * @param activityId 活动ID
   * @param request 删除请求
   * @return 是否删除成功
   */
  Boolean deleteAssociations(Long activityId, BatchDeleteActivityAssociationRequestDTO request);
}
