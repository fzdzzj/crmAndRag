# 变更提案 · 用户自服务信息更新防提权越权加固 (harden-user-self-update)

## 1. 现状与痛点

当前 `UserController` 存在两处用户修改入口：
1. `POST /user/update`：管理员修改接口，挂载 `@RequirePermission(PermissionOperates.SYSTEM_UPDATE_USER)`；
2. `POST /user/update/my`：自服务接口，在 `OpenEndpointRegistry` 登记为 `INTENTIONAL_OPEN`（仅需登录鉴权）。

然而在服务层实现中：
- 两个端点均委托至 `UserService.updateUser(UserDTO userDTO, Long id)`；
- 服务层仅有一处粗粒度校验：`if (userDTO.getRoleId() != null && Objects.equals(userDTO.getRoleId(), 1L)) throw new BaseException(MessageConstant.MASTER_IS_ONE);`；
- 其余字段（`roleId`, `deptId`, `status`）只要非空全部执行 `wrapper.set(...)` 更新！

**安全隐患（全面侦察台账 B-1，最高危）**：
任何普通登录用户通过构造请求调用 `POST /user/update/my`：
- 传入任意特权 `roleId`（如具有 606 `SYSTEM_ASSIGN_PERMISSION` 权限管理角色），即可将自己提升为特权业务角色；
- 传入任意 `deptId`，即可将自己任意换到其他部门，穿透 `queryUser` 的部门隔离以及相关数据权限范围；
- 传入 `status`，可篡改自身状态。

## 2. 方案与边界

1. **服务层自服务专用防线**：
   - 在 `UserService` 声明专用自服务方法 `void updateMyInfo(UserDTO userDTO, Long currentUserId)`；
   - 在 `UserServiceImpl` 中实现该方法：
     - **第一层 fail-closed 拦截**：严禁自服务请求包含特权治理字段。若 `userDTO.getRoleId() != null || userDTO.getDeptId() != null || userDTO.getStatus() != null`，直接抛出 `BaseException(ErrorCode.PERMISSION_DENIED, "自服务禁止修改角色、部门或状态等特权信息")`（12002）；
     - **第二层白名单构造**：即使未触发异常，SQL 更新 `LambdaUpdateWrapper` 严格限定仅能更新自服务白名单字段（`realName`, `phone`, `email`），且 `id` 强制使用上下文注入的 `currentUserId`；
2. **控制器端点解耦**：
   - `UserController.updateUserMy` 改为调用 `userService.updateMyInfo(userDTO, BaseUnit.getCurrentId())`；
   - `UserController.updateUser`（管理员通道）保持不变，继续调用 `userService.updateUser`；
3. **OpenEndpointRegistry 登记理由精准化**：
   - 将 `POST /user/update/my` 登记理由修订为精准表述：`"自服务：用户改自己基本信息（仅限姓名/手机/邮箱，严禁篡改角色/部门/状态，登录即可用）"`；
4. **测试守护与验证**：
   - 新增专项测试，覆盖：自服务正常白名单修改成功、自服务修改 roleId 拦截 12002、自服务修改 deptId 拦截 12002、自服务修改 status 拦截 12002、管理员正常修改角色部门。

## 3. 红线与非目标

- 零数据库迁移（`src/main/resources/db/migration` 零改动，下一可用号仍为 V43）；
- 零外部依赖引入（`pom.xml` 零改动）；
- 零破坏性接口契约变更（`openapi.yaml` 保持零 diff，不修改 `UserDTO` 结构）；
- 严禁触碰 `frontend/typed-router.d.ts`；
- A 档复核：涉及权限与安全语义变动，必须执行 A 档复核全流程。
