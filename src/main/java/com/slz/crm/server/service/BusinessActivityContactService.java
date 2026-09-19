package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.vo.BusinessActivityContactVO;
import java.util.List;

public interface BusinessActivityContactService {

  /**
   * 创建活动联系人关联
   *
   * @param dto 活动联系人关联DTO
   * @return 是否创建成功
   */
  Boolean create(BusinessActivityContactDTO dto);

  /**
   * 删除活动联系人关联
   *
   * @param idList 关联ID列表
   * @return 是否删除成功
   */
  Integer deleteByIds(List<Long> idList);

  /**
   * 更新活动联系人关联
   *
   * @param businessActivityContactDTOList 活动联系人关联DTO列表
   * @return 成功更新的数量
   */
  Integer updateList(List<BusinessActivityContactDTO> businessActivityContactDTOList);

  /**
   * 获取所有活动联系人关联
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 活动联系人关联列表
   */
  List<BusinessActivityContactVO> getAll(Integer pageNum, Integer pageSize);

  /**
   * 自定义分页查询活动联系人关联列表
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @param dto 查询条件DTO
   * @return 活动联系人关联列表
   */
  Page<BusinessActivityContactVO> query(
      Integer pageNum, Integer pageSize, BusinessActivityContactDTO dto);

  /**
   * 根据ID查询活动联系人关联详情
   *
   * @param id 关联ID
   * @return 活动联系人关联详情
   */
  BusinessActivityContactVO getDetailById(Long id);
}
