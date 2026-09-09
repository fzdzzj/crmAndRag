package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.RoleANDUserDTO;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.vo.RoleVO;

public interface RoleService {

    /**
     * 获取角色列表
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 角色列表
     */
    Page<RoleVO> list(Integer pageNum, Integer pageSize);

    /**
     * 获取当前用户角色
     * @return
     */
    RoleVO getMyRole();


    /**
     * 新增角色
     * @param roleEntity 角色实体
     * @return 是否添加成功
     */
    boolean save(RoleEntity roleEntity);

    /**
     * 删除角色
     * @param roleId 角色ID
     */
    void delete(Long roleId,Boolean isDelete);
}
