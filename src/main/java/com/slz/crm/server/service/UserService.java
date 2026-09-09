package com.slz.crm.server.service;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.GetUserDTO;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.vo.UserOptionVO;
import com.slz.crm.pojo.vo.UserVO;

import java.util.List;

public interface UserService {


    /**
     * 登录
     *
     * @param userDTO 用户数据
     * @return jwt token
     */
    String login(UserDTO userDTO);

    /**
     * 添加系统用户
     *
     * @param userDTO 用户数据
     * @return 返回初始密码
     */
    String addUser(UserDTO userDTO);

    /**
     * 根据用户ID查询用户信息
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    UserVO getById(Long userId);

    /**
     * 查询所有用户
     *
     * @return 所有用户
     */
    Page<UserVO> getAllUser(Integer pageNum, Integer pageSize);

    /**
     * 更新当前登录用户的密码
     *
     * @param password 新密码
     * @return 是否更新成功
     */
    boolean updatePassword(String password);

    /**
     * 管理员重置指定用户的密码
     *
     * @param userId 目标用户ID
     * @param newPassword 新密码
     * @return 是否重置成功
     */
    boolean resetUserPassword(Long userId, String newPassword);

    /**
     * 更新用户信息
     *
     * @param userDTO 用户数据
     */
    void updateUser(UserDTO userDTO,Long id);

    /**
     * 根据ID删除
     * @param id
     */
    boolean deleteById(List<Long> id);

    /**
     * 查询用户
     * @param dto
     * @return
     */
    Page<UserVO> findPage(GetUserDTO dto);

    /**
     * 查询全部在职用户的选择项（仅 id/姓名/部门，供协助人选择器等轻量场景）
     *
     * @return 在职用户选择项列表
     */
    List<UserOptionVO> listActiveOptions();
}
