package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.SysDeptDTO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.SysDeptService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 部门服务实现 */
@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDeptEntity>
    implements SysDeptService {

  @Autowired private UserMapper userMapper;

  @Autowired private CacheManager cacheManager;

  @Override
  public List<SysDeptEntity> listEnabled() {
    return list(
        new LambdaQueryWrapper<SysDeptEntity>()
            .eq(SysDeptEntity::getStatus, 1)
            .orderByAsc(SysDeptEntity::getSort)
            .orderByAsc(SysDeptEntity::getId));
  }

  @Override
  public List<SysDeptEntity> listAll() {
    return list(
        new LambdaQueryWrapper<SysDeptEntity>()
            .orderByAsc(SysDeptEntity::getSort)
            .orderByAsc(SysDeptEntity::getId));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean add(SysDeptDTO dto) {
    String deptName = requireValidNewDeptName(dto);

    // 同级/父级存在性与父链完整性校验
    validateParentChain(dto.getParentId(), null);
    // 状态只允许 0（停用）/1（启用）
    Integer status = dto.getStatus() == null ? 1 : dto.getStatus();
    requireValidDeptStatus(status);

    SysDeptEntity entity = new SysDeptEntity();
    entity.setDeptName(deptName);
    entity.setParentId(dto.getParentId());
    entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
    entity.setStatus(status);
    entity.setCreateTime(LocalDateTime.now());
    return save(entity);
  }

  /**
   * 校验新增部门名称：非空、长度不超 50、无同名部门（拆自 add，行为等价）。
   *
   * @param dto 新增部门请求
   * @return 去除首尾空白后的部门名称
   */
  private String requireValidNewDeptName(SysDeptDTO dto) {
    if (dto == null || dto.getDeptName() == null || dto.getDeptName().trim().isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_REQUIRED, "部门名称不能为空");
    }
    String deptName = dto.getDeptName().trim();
    if (deptName.length() > 50) {
      throw new BaseException(ErrorCode.PARAM_LENGTH_EXCEEDED, "部门名称不能超过50个字符");
    }
    // 同名部门禁止重复
    Long count =
        count(new LambdaQueryWrapper<SysDeptEntity>().eq(SysDeptEntity::getDeptName, deptName));
    if (count != null && count > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门名称已存在");
    }
    return deptName;
  }

  /**
   * 校验部门状态只允许 0（停用）/1（启用）（拆自 add，行为等价）。
   *
   * @param status 部门状态
   */
  private void requireValidDeptStatus(Integer status) {
    if (status != 0 && status != 1) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门状态只能为 0（停用）或 1（启用）");
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean update(SysDeptDTO dto) {
    if (dto == null || dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }
    SysDeptEntity entity = getById(dto.getId());
    if (entity == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("部门"));
    }
    if (dto.getDeptName() != null && !dto.getDeptName().trim().isEmpty()) {
      applyDeptNameUpdate(entity, dto);
    }
    if (dto.getParentId() != null) {
      validateParentChain(dto.getParentId(), dto.getId());
      entity.setParentId(dto.getParentId());
    }
    if (dto.getSort() != null) {
      entity.setSort(dto.getSort());
    }
    applyDeptStatusUpdate(entity, dto);
    entity.setUpdateTime(LocalDateTime.now());
    return updateById(entity);
  }

  /**
   * 应用部门名称变更：长度/重名校验并清除 deptName 缓存（拆自 update，行为等价；调用前需确认 deptName 非空白）。
   *
   * @param entity 部门实体
   * @param dto 更新请求
   */
  private void applyDeptNameUpdate(SysDeptEntity entity, SysDeptDTO dto) {
    String deptName = dto.getDeptName().trim();
    if (deptName.length() > 50) {
      throw new BaseException(ErrorCode.PARAM_LENGTH_EXCEEDED, "部门名称不能超过50个字符");
    }
    Long count =
        count(
            new LambdaQueryWrapper<SysDeptEntity>()
                .eq(SysDeptEntity::getDeptName, deptName)
                .ne(SysDeptEntity::getId, dto.getId()));
    if (count != null && count > 0) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门名称已存在");
    }
    boolean deptNameChanged = !Objects.equals(entity.getDeptName(), deptName);
    entity.setDeptName(deptName);
    if (deptNameChanged) {
      // 部门名称变更：清除 deptName 缓存，避免列表/详情继续显示旧名称
      Cache deptNameCache = cacheManager.getCache("deptName");
      if (deptNameCache != null) {
        deptNameCache.clear();
      }
    }
  }

  /**
   * 应用部门状态变更：只允许 0（停用）/1（启用）（拆自 update，行为等价）。
   *
   * @param entity 部门实体
   * @param dto 更新请求
   */
  private void applyDeptStatusUpdate(SysDeptEntity entity, SysDeptDTO dto) {
    if (dto.getStatus() != null) {
      if (dto.getStatus() != 0 && dto.getStatus() != 1) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "部门状态只能为 0（停用）或 1（启用）");
      }
      entity.setStatus(dto.getStatus());
    }
  }

  /**
   * 校验上级部门：父部门必须存在，且沿父链向上不能遇到当前部门（防止循环引用）。
   *
   * @param parentId 拟设置的上级部门 ID
   * @param selfId 当前部门 ID（新增时传 null）
   */
  private void validateParentChain(Long parentId, Long selfId) {
    if (parentId == null) {
      return;
    }
    if (Objects.equals(parentId, selfId)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "上级部门不能是自己");
    }
    SysDeptEntity parent = getById(parentId);
    if (parent == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("上级部门"));
    }
    // 沿父链向上遍历至根节点；用已访问集合检测环，任意深度均可靠（不设层数上限，避免深层链绕过校验）
    Set<Long> visited = new HashSet<>();
    Long cursor = parent.getParentId();
    while (cursor != null) {
      if (Objects.equals(cursor, selfId)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "不能选择自身或自己的子部门作为上级部门");
      }
      // 父链出现重复节点：数据中已存在循环引用，明确拒绝而非静默放过
      if (!visited.add(cursor)) {
        throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "上级部门父链存在循环引用，请先修复部门层级数据");
      }
      SysDeptEntity ancestor = getById(cursor);
      if (ancestor == null) {
        break; // 父链断裂（历史异常数据），终止遍历
      }
      cursor = ancestor.getParentId();
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Boolean delete(Long id) {
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
    }
    SysDeptEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS.getMessage().formatted("部门"));
    }
    // 有子部门的部门禁止删除，避免 parentId 悬空
    Long childCount =
        count(new LambdaQueryWrapper<SysDeptEntity>().eq(SysDeptEntity::getParentId, id));
    if (childCount != null && childCount > 0) {
      throw new BaseException(ErrorCode.DATA_DELETE_FAILED, "该部门下存在子部门，禁止删除");
    }
    // 有用户引用的部门禁止删除
    Long userCount =
        userMapper.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getDeptId, id));
    if (userCount != null && userCount > 0) {
      throw new BaseException(ErrorCode.DATA_DELETE_FAILED, "该部门下存在用户，禁止删除");
    }
    return removeById(id);
  }
}
