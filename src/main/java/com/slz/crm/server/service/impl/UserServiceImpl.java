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
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.UserOptionVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.UserService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, UserEntity> implements UserService {

  @Autowired private UserMapper userMapper;

  @Autowired private JwtProperties jwtProperties;

  @Autowired private RoleMapper roleMapper;

  @Autowired private SysDeptMapper sysDeptMapper;

  @Autowired private DataConvertService dataConvertService;

  @Override
  public String login(UserDTO userDTO) {
    // 先将 Base64 加密的密码解密为明文
    String rawPassword = BaseUnit.decryptBase64(userDTO.getPassword());

    UserEntity user =
        userMapper.selectOne(
            new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, userDTO.getEmail()));

    if (user == null || !PasswordHashUtil.matches(rawPassword, user.getPassword())) {
      throw new BaseException(ErrorCode.PASSWORD_OR_EMAIL_ERROR);
    }

    // 惰性透明升级：存量 MD5 密文在首次成功登录后改写为 BCrypt
    if (PasswordHashUtil.isMd5(user.getPassword())) {
      UserEntity upgrade = new UserEntity();
      upgrade.setId(user.getId());
      upgrade.setPassword(PasswordHashUtil.hash(rawPassword));
      userMapper.updateById(upgrade);
    }

    // 检查用户角色是否被删除
    if (user.getRoleId() != null && user.getRoleId() != 0) {
      RoleEntity role = roleMapper.selectById(user.getRoleId());
      if (role == null || Boolean.TRUE.equals(role.getIsDeleted())) {
        throw new BaseException("该用户角色已被删除，无法登录，请联系管理员");
      }
    }

    Map<String, Object> map = new HashMap<>();

    map.put("userID", user.getId());

    return JwtUntil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), map);
  }

  @Override
  public String addUser(UserDTO userDTO) {

    if (userDTO.getRoleId() == null) {
      throw new BaseException(ErrorCode.ROLE_NOT_EXISTS.getMessage());
    }
    UserDeptSupport.validateDeptId(userDTO.getDeptId(), sysDeptMapper);
    // TODO 管理员不能添加管理员
    //        if(userDTO.getRoleId() == 1){
    //            throw new BaseException(MessageConstant.MASTER_IS_ONE);
    //        }
    // 判断邮箱格式
    if (!ValidationUtils.isValidEmail(userDTO.getEmail())) {
      throw new BaseException(ErrorCode.EMAIL_FORMAT_ERROR.getMessage());
    }
    // 判断手机号格式
    if (!ValidationUtils.isValidMobile(userDTO.getPhone())) {
      throw new BaseException(ErrorCode.PHONE_FORMAT_ERROR.getMessage());
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
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("用户"));
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
  public boolean updatePassword(String password) {
    Long currentId = BaseUnit.getCurrentId();
    // 先将 Base64 加密的密码解密为明文
    String decryptedPassword = BaseUnit.decryptBase64(password);
    UserEntity userEntity = new UserEntity();
    userEntity.setId(currentId);
    userEntity.setPassword(PasswordHashUtil.hash(decryptedPassword));
    int i = userMapper.updateById(userEntity);
    if (i == 0) {
      throw new ServiceException(ErrorCode.UPDATE_FAILED.getMessage());
    }
    return true;
  }

  @Override
  public boolean resetUserPassword(Long userId, String newPassword) {
    // 验证目标用户是否存在
    UserEntity targetUser = userMapper.selectById(userId);
    if (targetUser == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("用户"));
    }

    // 验证新密码不能为空
    if (newPassword == null || newPassword.trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage().formatted("新密码"));
    }

    // 更新密码
    UserEntity userEntity = new UserEntity();
    userEntity.setId(userId);
    userEntity.setPassword(PasswordHashUtil.hash(newPassword));
    int i = userMapper.updateById(userEntity);
    if (i == 0) {
      throw new ServiceException(ErrorCode.UPDATE_FAILED.getMessage());
    }
    return true;
  }

  @Override
  @CacheEvict(value = "userName", key = "#id")
  public void updateUser(UserDTO userDTO, Long id) {
    // 更新个人信息时禁止改密码
    userDTO.setPassword(null);

    // 校验用户存在
    UserEntity userEntity = userMapper.selectById(id);
    if (userEntity == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("用户"));
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
    if (userDTO.getRoleId() != null) {
      wrapper.set(UserEntity::getRoleId, userDTO.getRoleId());
    }
    if (userDTO.getStatus() != null) {
      wrapper.set(UserEntity::getStatus, userDTO.getStatus());
    }
    wrapper.set(UserEntity::getUpdateTime, LocalDateTime.now());

    int i = userMapper.update(null, wrapper);
    if (i == 0) {
      throw new BaseException(ErrorCode.UPDATE_FAILED.getMessage());
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
      throw new BaseException("删除失败，请注意用户状态");
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
    List<UserOptionVO> result;
    if (users.isEmpty()) {
      result = Collections.emptyList();
    } else {
      Set<Long> deptIds =
          users.stream()
              .map(UserEntity::getDeptId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      Map<Long, String> deptNameMap =
          deptIds.isEmpty() ? Collections.emptyMap() : dataConvertService.getDeptNames(deptIds);
      result =
          users.stream()
              .map(
                  user -> {
                    UserOptionVO vo = new UserOptionVO();
                    vo.setId(user.getId());
                    vo.setRealName(user.getRealName());
                    vo.setDeptId(user.getDeptId());
                    if (user.getDeptId() != null) {
                      vo.setDeptName(deptNameMap.get(user.getDeptId()));
                    }
                    return vo;
                  })
              .collect(Collectors.toList());
    }
    return result;
  }
}
