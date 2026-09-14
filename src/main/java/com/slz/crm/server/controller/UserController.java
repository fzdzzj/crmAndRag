package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.GetUserDTO;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.vo.UserOptionVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户
 */
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {


    private final UserService userService;

    @Autowired
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 登录
     *
     * @param userDTO
     * @return jwt token
     */
    @PostMapping("/login")
    private Result<String> login(@RequestBody UserDTO userDTO) {
        String token = userService.login(userDTO);
        return Result.success(token);
    }

    /**
     * 添加系统用户
     *
     * @param userDTO
     * @return 初始密码
     */
    @PostMapping("/add")
    @RequirePermission(PermissionOperates.SYSTEM_CREATE_USER)
    private Result<String> addUser(@RequestBody UserDTO userDTO) {
        String password = userService.addUser(userDTO);
        return Result.success(password);
    }

    /**
     * 条件查询用户
     * @param dto
     * @return
     */
    @PostMapping("/find")
    // apply-permission-matrix 任务 3.1：条件查用户挂查看权限
    @RequirePermission(PermissionOperates.SYSTEM_VIEW_USER)
    private Result<Page<UserVO>> findUser(GetUserDTO dto) {
        Page<UserVO> ans = userService.findPage(dto);
        return Result.success(ans);
    }

    /**
     * 查询所有用户
     *
     * @return 所有用户
     */
    @GetMapping
    @RequirePermission(PermissionOperates.SYSTEM_VIEW_USER)
    private Result<Page<UserVO>> getAllUser(Integer pageNum, Integer pageSize) {
        if (pageNum == null || pageSize == null) {
            pageNum = 1;
            pageSize = 10;
        }
        Page<UserVO> userDTOS = userService.getAllUser(pageNum, pageSize);
        return Result.success(userDTOS);
    }



    /**
     * 更新密码
     *
     * @param password 新密码
     * @return
     */
    @PostMapping("/password")
//    @RequirePermission(PermissionOperates.SYSTEM_UPDATE_PASSWORD)
    private Result<Boolean> updatePassword(@RequestBody String password) {
        boolean b = userService.updatePassword(password);
        return Result.success();
    }

    /**
     * 管理员重置指定用户的密码
     *
     * @param resetUserPasswordDTO 包含目标用户ID和新密码
     * @return
     */
    @PostMapping("/password/reset")
    @RequirePermission(PermissionOperates.SYSTEM_UPDATE_USER)
    private Result<Boolean> resetUserPassword(@RequestBody com.slz.crm.pojo.dto.ResetUserPasswordDTO resetUserPasswordDTO) {
        boolean b = userService.resetUserPassword(resetUserPasswordDTO.getUserId(), resetUserPasswordDTO.getNewPassword());
        return Result.success(b);
    }

    /**
     * 更新用户信息(无法修改密码)
     *
     * @param userDTO 用户信息
     * @return
     */
    @PostMapping("/update")
    @RequirePermission(PermissionOperates.SYSTEM_UPDATE_USER)
    private Result<String> updateUser(@RequestBody UserDTO userDTO) {
        userService.updateUser(userDTO,userDTO.getId());
        return Result.success();
    }

    /**
     * 更新当前用户信息(无法修改密码)
     *
     * @param userDTO 用户信息
     * @return
     */
    @PostMapping("/update/my")
    private Result<String> updateUserMy(@RequestBody UserDTO userDTO) {
        userService.updateUser(userDTO,BaseUnit.getCurrentId());
        return Result.success();
    }

    /**
     * 删除用户
     * @param ids
     * @return
     */
    @DeleteMapping
    // apply-permission-matrix 任务 3.1：删除用户挂修改用户权限
    @RequirePermission(PermissionOperates.SYSTEM_UPDATE_USER)
    public Result<Boolean> delete(@RequestParam List<Long> ids) {
        boolean b = userService.deleteById(ids);
        return Result.success(b);
    }

    @GetMapping("/my")
    public Result<UserVO> getMyUser() {
        RoleAO currentRole = BaseUnit.getCurrentRole();
        Long id = currentRole.getId();
        UserVO byId = userService.getById(id);
        return Result.success(byId);
    }

    /**
     * 查询全部在职用户选择项（供协助人选择器等轻量场景，登录即可，不要求系统权限）
     *
     * @return 在职用户选择项列表
     */
    @GetMapping("/options")
    public Result<List<UserOptionVO>> options() {
        return Result.success(userService.listActiveOptions());
    }

}
