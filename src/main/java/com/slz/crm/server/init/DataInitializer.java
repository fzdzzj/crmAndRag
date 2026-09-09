package com.slz.crm.server.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.PermissionsMapper;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据初始化启动器
 * 在权限同步完成后，自动初始化：
 * 1. 管理员角色（拥有所有权限）
 * 2. 管理员用户
 * 3. 角色-权限绑定关系
 *
 * 可以通过配置控制是否启用：
 * - application.yml: data.init.enabled=true
 * - 启动参数: --data.init.enabled=true
 * - 环境变量: DATA_INIT_ENABLED=true
 *
 * 执行顺序：使用 @Order(2) 确保在权限同步(@Order(1))之后执行
 */
@Component
@Slf4j
@Order(2)
@ConditionalOnProperty(
    prefix = "data.init",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class DataInitializer {

    @Value("${data.init.enabled:true}")
    private boolean dataInitEnabled;

    @Value("${data.init.admin.email:admin@crm.com}")
    private String adminEmail;

    @Value("${data.init.admin.password:admin123}")
    private String adminPassword;

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private PermissionsMapper permissionsMapper;

    @Resource
    private SysDeptMapper sysDeptMapper;

    /**
     * 执行数据初始化
     * 由 CrmApplication.main() 手动调用，确保在 PermissionSyncRunner 之后执行
     */
    @Transactional(rollbackFor = Exception.class)
    public void start() {
        if (!dataInitEnabled) {
            log.info("========================================");
            log.info("数据初始化已禁用，跳过执行");
            log.info("========================================");
            return;
        }

        log.info("========================================");
        log.info("开始数据初始化...");
        log.info("配置状态: {}", dataInitEnabled ? "启用" : "禁用");
        log.info("========================================");

        // 1. 初始化管理员角色
        Long adminRoleId = initAdminRole();

        // 2. 初始化管理员用户
        Long adminUserId = initAdminUser(adminRoleId);

        // 3. 授予管理员角色所有权限
        grantAllPermissionsToRole(adminRoleId, adminUserId);

        // 4. 初始化默认部门，并把未分配部门的用户（含管理员）归入默认部门
        Long defaultDeptId = initDefaultDept();

        log.info("========================================");
        log.info("数据初始化完成");
        log.info("  管理员邮箱: {}", adminEmail);
        log.info("  管理员密码: {}（明文，请登录后修改）", adminPassword);
        log.info("  管理员角色ID: {}", adminRoleId);
        log.info("  管理员用户ID: {}", adminUserId);
        log.info("  默认部门ID: {}", defaultDeptId);
        log.info("========================================");
    }

    /**
     * 初始化默认部门
     * 若无部门则创建“默认部门”，并把 deptId 为空/0 的存量用户（含管理员）归入默认部门
     *
     * @return 默认部门ID
     */
    private Long initDefaultDept() {
        log.info("--- 检查默认部门 ---");
        SysDeptEntity defaultDept = sysDeptMapper.selectOne(
            new LambdaQueryWrapper<SysDeptEntity>().eq(SysDeptEntity::getDeptName, "默认部门")
        );
        if (defaultDept == null) {
            defaultDept = new SysDeptEntity();
            defaultDept.setDeptName("默认部门");
            defaultDept.setSort(0);
            defaultDept.setStatus(1);
            defaultDept.setCreateTime(LocalDateTime.now());
            sysDeptMapper.insert(defaultDept);
            log.info("创建默认部门成功，ID: {}", defaultDept.getId());
        } else {
            log.info("默认部门已存在，ID: {}", defaultDept.getId());
        }

        List<UserEntity> usersWithoutDept = userMapper.selectList(
            new LambdaQueryWrapper<UserEntity>()
                .and(w -> w.isNull(UserEntity::getDeptId).or().eq(UserEntity::getDeptId, 0L))
        );
        if (!usersWithoutDept.isEmpty()) {
            for (UserEntity user : usersWithoutDept) {
                UserEntity updateUser = new UserEntity();
                updateUser.setId(user.getId());
                updateUser.setDeptId(defaultDept.getId());
                userMapper.updateById(updateUser);
            }
            log.info("已将 {} 个未分配部门的用户归入默认部门", usersWithoutDept.size());
        }
        return defaultDept.getId();
    }

    /**
     * 初始化管理员角色
     * 超管角色为 "Admin"（roleId=1），与全项目 roleId==1 的超管判断及测试数据保持一致；
     * 若不存在则创建
     *
     * @return 管理员角色ID
     */
    private Long initAdminRole() {
        log.info("--- 检查管理员角色 ---");
        RoleEntity adminRole = roleMapper.selectOne(
            new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getRoleName, "Admin")
                .eq(RoleEntity::getIsDeleted, false)
        );

        if (adminRole != null) {
            log.info("管理员角色已存在，ID: {}, 角色名: {}", adminRole.getId(), adminRole.getRoleName());
            return adminRole.getId();
        }

        adminRole = new RoleEntity();
        adminRole.setRoleName("Admin");
        adminRole.setRoleDesc("系统管理员（超管），拥有所有权限");
        roleMapper.insert(adminRole);
        log.info("创建管理员角色成功，ID: {}", adminRole.getId());
        return adminRole.getId();
    }

    /**
     * 初始化管理员用户
     * 如果管理员邮箱对应的用户已存在则跳过，否则创建
     *
     * @param roleId 管理员角色ID
     * @return 管理员用户ID
     */
    private Long initAdminUser(Long roleId) {
        log.info("--- 检查管理员用户 ---");
        UserEntity adminUser = userMapper.selectOne(
            new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, adminEmail)
        );

        if (adminUser != null) {
            log.info("管理员用户已存在，ID: {}, 邮箱: {}", adminUser.getId(), adminUser.getEmail());

            // 确保管理员用户拥有正确的角色
            if (!roleId.equals(adminUser.getRoleId())) {
                log.warn("管理员用户角色ID不匹配，更新角色ID: {} -> {}", adminUser.getRoleId(), roleId);
                UserEntity updateUser = new UserEntity();
                updateUser.setId(adminUser.getId());
                updateUser.setRoleId(roleId);
                userMapper.updateById(updateUser);
            }

            // 确保状态正常
            if (adminUser.getStatus() == null || adminUser.getStatus() != 1) {
                log.warn("管理员用户状态异常({})，修复为正常状态", adminUser.getStatus());
                UserEntity updateUser = new UserEntity();
                updateUser.setId(adminUser.getId());
                updateUser.setStatus(1);
                userMapper.updateById(updateUser);
            }

            return adminUser.getId();
        }

        adminUser = new UserEntity();
        adminUser.setEmail(adminEmail);
        adminUser.setRealName("系统管理员");
        adminUser.setPassword(DigestUtils.md5DigestAsHex(adminPassword.getBytes()));
        adminUser.setRoleId(roleId);
        adminUser.setStatus(1); // 正常状态
        adminUser.setPhone("13800000000");
        // creator_id 先留空，插入后再设置为自引用
        userMapper.insert(adminUser);

        // 设置创建者为自身（自引用）
        adminUser.setCreatorId(adminUser.getId());
        userMapper.updateById(adminUser);

        log.info("创建管理员用户成功，ID: {}, 邮箱: {}", adminUser.getId(), adminEmail);
        return adminUser.getId();
    }

    /**
     * 将数据库中所有权限授予管理员角色
     * 只分配尚未拥有的权限，避免重复插入
     *
     * @param roleId    管理员角色ID
     * @param creatorId 创建者ID（管理员用户ID）
     */
    private void grantAllPermissionsToRole(Long roleId, Long creatorId) {
        log.info("--- 分配权限到管理员角色 ---");

        // 查询角色已有的权限ID列表
        List<Integer> existingPermIds = permissionsMapper.selectPermissionIdsByRoleId(roleId.intValue());

        // 查询数据库中所有权限
        List<PermissionsEntity> allPermissions = permissionsMapper.selectList(null);
        if (allPermissions.isEmpty()) {
            log.warn("数据库中没有任何权限数据，请确保权限同步已执行");
            return;
        }

        // 筛出未分配的权限
        List<Integer> missingPermIds = allPermissions.stream()
            .map(p -> p.getId().intValue())
            .filter(id -> !existingPermIds.contains(id))
            .collect(Collectors.toList());

        if (missingPermIds.isEmpty()) {
            log.info("管理员角色已拥有所有权限（{} 个），无需分配", existingPermIds.size());
            return;
        }

        permissionsMapper.batchAddPermissionToRole(roleId.intValue(), missingPermIds, creatorId);
        log.info("为管理员角色新增 {} 个权限（总共 {} 个权限，原有 {} 个）",
            missingPermIds.size(), allPermissions.size(), existingPermIds.size());
    }
}
