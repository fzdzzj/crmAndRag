package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.vo.BusinessActivityUserVO;

import java.util.List;

public interface BusinessActivityUserService {

    /**
     * 创建活动用户关联
     * @param dto 活动用户关联DTO
     * @return 是否创建成功
     */
    Boolean create(BusinessActivityUserDTO dto);

    /**
     * 删除活动用户关联
     * @param idList 关联ID列表
     * @return 是否删除成功
     */
    Integer deleteByIds(List<Long> idList);

    /**
     * 更新活动用户关联
     * @param businessActivityUserDTOList 活动用户关联DTO列表
     * @return 成功更新的数量
     */
    Integer updateList(List<BusinessActivityUserDTO> businessActivityUserDTOList);

    /**
     * 获取所有活动用户关联
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 活动用户关联列表
     */
    List<BusinessActivityUserVO> getAll(Integer pageNum, Integer pageSize);

    /**
     * 自定义分页查询活动用户关联列表
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param dto 查询条件DTO
     * @return 活动用户关联列表
     */
    Page<BusinessActivityUserVO> query(Integer pageNum, Integer pageSize, BusinessActivityUserDTO dto);

    /**
     * 根据ID查询活动用户关联详情
     * @param id 关联ID
     * @return 活动用户关联详情
     */
    BusinessActivityUserVO getDetailById(Long id);
}
