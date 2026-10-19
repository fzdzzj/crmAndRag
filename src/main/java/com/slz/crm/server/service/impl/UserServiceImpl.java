package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.untils.*;
import com.slz.crm.pojo.dto.GetUserDTO;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.dto.UserUpdatePasswordDTO;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.UserOptionVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.UserService;
import com.slz.crm.server.service.support.LoginAttemptService;
import com.slz.crm.server.service.support.TokenBlacklistService;
import com.slz.crm.server.service.support.UserPasswordSupport;
import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, UserEntity> implements UserService {

  @Autowired private UserMapper userMapper;

  @Autowired private JwtProperties jwtProperties;

  @Autowired private RoleMapper roleMapper;

  @Autowired private SysDeptMapper sysDeptMapper;

  @Autowired private DataConvertService dataConvertService;

  @Autowired private LoginAttemptService loginAttemptService;

  @Autowired private TokenBlacklistService tokenBlacklistService;

  @Override
  public String login(UserDTO userDTO) {
    UserLoginSupport.checkLocked(loginAttemptService, userDTO.getEmail());

    String rawPassword = BaseUnit.decryptBase64(userDTO.getPassword());
    UserEntity user =
        userMapper.selectOne(
            new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, userDTO.getEmail()));

    UserLoginSupport.verifyCredentials(user, rawPassword, userDTO.getEmail(), loginAttemptService);
    UserLoginSupport.upgradeLegacyPassword(user, rawPassword, userMapper);
    UserLoginSupport.validateUserRole(user, roleMapper);

    Map<String, Object> map = new HashMap<>();
    map.put("userID", user.getId());
    return JwtUntil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), map);
  }

  /**
   * 添加系统用户（harden-admin-uniqueness-and-hygiene 任务 1.1：终极管理员唯一性防护，禁止添加终极管理员）。
   *
   * @param userDTO 用户数据传输对象
   * @return 生成结果
   */
  @Override
  public String addUser(UserDTO userDTO) {

    if (userDTO.getRoleId() == null) {
      throw new BaseException(ErrorCode.ROLE_NOT_EXISTS);
    }
    UserDeptSupport.validateDeptId(userDTO.getDeptId(), sysDeptMapper);
    // harden-admin-uniqueness-and-hygiene 任务 1.1：终极管理员唯一性防护，禁止添加终极管理员
    if (Objects.equals(userDTO.getRoleId(), 1L)) {
      throw new BaseException(ErrorCode.ADMIN_EXISTS_ONLY_ONE, MessageConstant.MASTER_IS_ONE);
    }
    // 判断邮箱格式
    if (!ValidationUtils.isValidEmail(userDTO.getEmail())) {
      throw new BaseException(ErrorCode.EMAIL_FORMAT_ERROR);
    }
    // 判断手机号格式
    if (!ValidationUtils.isValidMobile(userDTO.getPhone())) {
      throw new BaseException(ErrorCode.PHONE_FORMAT_ERROR);
    }

    String password = "crm";
    // 邮箱前四位
    if (userDTO.getEmail() == null || userDTO.getEmail().length() < 4) {
      throw new BaseException(ErrorCode.EMAIL_FORMAT_ERROR);
    }
    password = password + userDTO.getEmail().substring(0, 4);

    UserEntity userEntity = new UserEntity();
    BeanUtils.copyProperties(userDTO, userEntity);

    userEntity.setCreatorId(BaseUnit.getCurrentId());

    userEntity.setPassword(PasswordHashUtil.hash(password));
    // 默认状态
    if (userDTO.getStatus() != null) {
      userEntity.setStatus(userDTO.getStatus());
    } else {
      userEntity.setStatus(0);
    }
    userMapper.insert(userEntity);

    return password;
  }

  @Override
  public UserVO getById(Long userId) {

    UserEntity userEntity = userMapper.selectById(userId);

    if (userEntity == null) {
      throw new BaseException(
          ErrorCode.ID_NOT_EXISTS, ErrorCode.ID_NOT_EXISTS.getMessage().formatted("用户"));
    }

    UserVO vo = UserVO.fromEntity(userEntity);
    if (userEntity.getDeptId() != null) {
      vo.setDeptName(dataConvertService.getDeptName(userEntity.getDeptId()));
    }
    return vo;
  }

  @Override
  @Privacy
  public Page<UserVO> getAllUser(Integer pageNum, Integer pageSize) {
    Page<UserEntity> userEntityPage = new Page<>(pageNum, pageSize);

    LambdaQueryWrapper<UserEntity> in =
        new LambdaQueryWrapper<UserEntity>().in(UserEntity::getStatus, 0, 1, 2);
    Page<UserEntity> userEntityPage1 = userMapper.selectPage(userEntityPage, in);

    Page<UserVO> ans = new Page<>();
    BeanUtils.copyProperties(userEntityPage1, ans);

    List<UserVO> userVOS = new ArrayList<>();
    userEntityPage1
        .getRecords()
        .forEach(
            userEntity -> {
              UserVO userVO = UserVO.fromEntity(userEntity);
              userVOS.add(userVO);
            });
    ans.setRecords(userVOS);
    UserDeptSupport.fillDeptNames(userVOS, dataConvertService);

    return ans;
  }

  @Override
  public boolean updatePassword(UserUpdatePasswordDTO dto, Long currentId) {
    return UserPasswordSupport.changePassword(
        userMapper, currentId, dto.getOldPassword(), dto.getNewPassword());
  }

  @Override
  public boolean resetUserPassword(Long userId, String newPassword) {
    // 验证目标用户是否存在
    UserEntity targetUser = userMapper.selectById(userId);
    if (targetUser == null) {
      throw new BaseException(
          ErrorCode.ID_NOT_EXISTS, ErrorCode.ID_NOT_EXISTS.getMessage().formatted("用户"));
    }

    // 终极超管防篡改：非超管本人禁止重置超管密码（12002）
    if (targetUser.getRoleId() != null
        && Objects.equals(targetUser.getRoleId(), 1L)
        && !Objects.equals(BaseUnit.getCurrentId(), targetUser.getId())) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "仅超级管理员本人可重置超级管理员密码");
    }

    // 验证新密码不能为空
    if (newPassword == null || newPassword.trim().isEmpty()) {
      throw new BaseException(
          ErrorCode.PARAM_EMPTY, ErrorCode.PARAM_EMPTY.getMessage().formatted("新密码"));
    }

    // 更新密码
    UserEntity userEntity = new UserEntity();
    userEntity.setId(userId);
    userEntity.setPassword(PasswordHashUtil.hash(newPassword));
    int i = userMapper.updateById(userEntity);
    if (i == 0) {
      throw new ServiceException(ErrorCode.UPDATE_FAILED.getMessage());
    }
    log.info("管理员重置用户密码成功: operatorId={}, targetUserId={}", BaseUnit.getCurrentId(), userId);
    return true;
  }

  /**
   * 更新用户信息（harden-admin-uniqueness-and-hygiene 任务 1.2：终极管理员唯一性防护，禁止修改为终极管理员）。
   *
   * @param userDTO 用户数据传输对象
   * @param id 用户ID
   */
  @Override
  @CacheEvict(value = "userName", key = "#id")
  public void updateUser(UserDTO userDTO, Long id) {
    // 更新个人信息时禁止改密码
    userDTO.setPassword(null);

    // 校验用户存在
    UserEntity userEntity = userMapper.selectById(id);
    if (userEntity == null) {
      throw new BaseException(
          ErrorCode.ID_NOT_EXISTS, ErrorCode.ID_NOT_EXISTS.getMessage().formatted("用户"));
    }

    // 只更新传入的非空字段；null 表示“不修改”
    LambdaUpdateWrapper<UserEntity> wrapper = new LambdaUpdateWrapper<>();
    wrapper.eq(UserEntity::getId, id);
    if (userDTO.getRealName() != null) {
      wrapper.set(UserEntity::getRealName, userDTO.getRealName());
    }
    if (userDTO.getPhone() != null) {
      wrapper.set(UserEntity::getPhone, userDTO.getPhone());
    }
    if (userDTO.getEmail() != null) {
      wrapper.set(UserEntity::getEmail, userDTO.getEmail());
    }
    if (userDTO.getDeptId() != null) {
      UserDeptSupport.validateDeptId(userDTO.getDeptId(), sysDeptMapper);
      wrapper.set(UserEntity::getDeptId, userDTO.getDeptId());
    }
    // harden-admin-uniqueness-and-hygiene 任务 1.2：终极管理员唯一性防护，禁止修改为终极管理员
    if (userDTO.getRoleId() != null && Objects.equals(userDTO.getRoleId(), 1L)) {
      throw new BaseException(ErrorCode.ADMIN_EXISTS_ONLY_ONE, MessageConstant.MASTER_IS_ONE);
    }
    if (userDTO.getRoleId() != null) {
      wrapper.set(UserEntity::getRoleId, userDTO.getRoleId());
    }
    if (userDTO.getStatus() != null) {
      wrapper.set(UserEntity::getStatus, userDTO.getStatus());
    }
    wrapper.set(UserEntity::getUpdateTime, LocalDateTime.now());

    int i = userMapper.update(null, wrapper);
    if (i == 0) {
      throw new BaseException(ErrorCode.UPDATE_FAILED);
    }
  }

  /**
   * 自服务更新当前登录用户基本信息（harden-user-self-update 任务 1.1：防自服务提权）。
   *
   * <p>第一层 fail-closed：特权治理字段（roleId/deptId/status）任何非空直接拒绝（12002）； 第二层白名单：SQL 更新构造仅允许
   * realName/phone/email（构造拆至 {@link UserSelfUpdateSupport}）， 目标行强制绑定 currentUserId，不信任请求体中的任何身份字段。
   *
   * @param userDTO 用户数据传输对象
   * @param currentUserId 当前登录用户ID（上下文注入）
   */
  @Override
  @CacheEvict(value = "userName", key = "#currentUserId")
  public void updateMyInfo(UserDTO userDTO, Long currentUserId) {
    if (userDTO.getRoleId() != null || userDTO.getDeptId() != null || userDTO.getStatus() != null) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "自服务禁止修改角色、部门或状态等特权信息");
    }

    int i =
        userMapper.update(
            null, UserSelfUpdateSupport.buildWhitelistWrapper(userDTO, currentUserId));
    if (i == 0) {
      throw new BaseException(ErrorCode.UPDATE_FAILED);
    }
  }

  /**
   * @param id
   * @return
   */
  @Override
  @CacheEvict(value = "userName", allEntries = true)
  @Transactional(rollbackFor = Exception.class)
  public boolean deleteById(List<Long> id) {

    List<UserEntity> userEntities =
        userMapper.selectList(
            new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getId, id)
                .in(UserEntity::getStatus, 0, 2));

    if (userEntities.size() != id.size()) {
      throw new BaseException(ErrorCode.DATA_DELETE_FAILED, "删除失败，请注意用户状态");
    }

    // 使用for循环替代forEach，确保异常能正确抛出
    for (UserEntity userEntity : userEntities) {
      userEntity.setStatus(3);
      userEntity.setEmail(null);
      userEntity.setPhone(null);
      userMapper.updateById(userEntity);
    }

    return true;
  }

  @Override
  public Page<UserVO> findPage(GetUserDTO dto) {

    LambdaQueryWrapper<UserEntity> wp = new LambdaQueryWrapper<>();

    UserQueryFilterSupport.applyUserPageFilters(wp, dto, userMapper, roleMapper);

    Page<UserEntity> page = new Page<>(dto.getPageNum(), dto.getPageSize());

    Page<UserEntity> userEntityPage = userMapper.selectPage(page, wp);

    Page<UserVO> ans = new Page<>();
    BeanUtils.copyProperties(userEntityPage, ans);
    List<UserVO> userVOS = new ArrayList<>();

    page.getRecords()
        .forEach(
            userEntity -> {
              UserVO userVO = UserVO.fromEntity(userEntity);
              userVOS.add(userVO);
            });

    ans.setRecords(userVOS);
    UserDeptSupport.fillDeptNames(userVOS, dataConvertService);

    return ans;
  }

  @Override
  public List<UserOptionVO> listActiveOptions() {
    List<UserEntity> users =
        userMapper.selectList(
            new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getStatus, 1)
                .orderByAsc(UserEntity::getId));
    return UserDeptSupport.toActiveOptions(users, dataConvertService);
  }

  @Override
  public void logout(String token) {
    if (token != null && !token.isBlank() && tokenBlacklistService != null) {
      tokenBlacklistService.addToBlacklist(token);
    }
  }
}
