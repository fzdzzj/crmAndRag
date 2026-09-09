package com.slz.crm.platform.contract;

import com.slz.crm.common.enumeration.DataScopeLevel;
import java.util.List;

/**
 * 数据范围解析契约（冻结契约，Lane A 实现，Lane C 消费）。
 *
 * <p>落点约定（重要，勿走偏）：</p>
 * <ul>
 *   <li>行级过滤的真正实现 = {@code DataScopeServiceImpl}（解析范围） +
 *       {@code QueryWrapperAspect}（把范围注入 MyBatis-Plus Wrapper）；</li>
 *   <li>不存在 {@code MyDataPermissionHandler}——源仓里它只出现在 Javadoc 注释里，
 *       任何 lane 都不要去找这个类（已在本 wave 核实）；</li>
 *   <li>本契约的入参 MUST 是 {@link UserContext}（含 deptId），
 *       因为 {@code RoleAO} 缺 deptId，无法表达“本部门/本部门及子部门”语义。</li>
 * </ul>
 *
 * <p>为什么抽象成接口而不是直接复用 {@code DataScopeService}：助手侧的 {@code queryKnowledgeBase}
 * 工具需要跨域判定（知识库成员表不是 CRM 业务表），接口化后可替换实现做单元测试，
 * 也避免助手模块反向依赖 server 包内部实现。</p>
 */
public interface DataScope {

    /**
     * 解析用户的数据范围级别。
     *
     * @param user 当前用户上下文
     * @return 范围级别；NONE 表示该权限点不参与行级过滤
     */
    DataScopeLevel levelOf(UserContext user);

    /**
     * 解析可见部门集合（含子部门，按 {@code sys_dept.parentId} 递归）。
     *
     * <p>注意：D6 已确认“上级查看下属”由 部门树 + {@code sys_dept.leaderId} 组合表达；
     * 本方法在 {@code leaderId} 迁移落地前仅按部门树推导，不负责负责人语义。</p>
     *
     * @param user 当前用户上下文
     * @return 可见部门 id 集合；ALL 时返回空集合（表示“无限制”，调用方不要当“无权限”）
     */
    List<Long> visibleDeptIds(UserContext user);

    /**
     * 判断某条业务数据是否可见（记录级判定，供助手/知识库复用）。
     *
     * @param user       当前用户上下文
     * @param ownerId    数据负责人用户 id
     * @param ownerDeptId 数据所属部门 id
     * @return true 表示可见；实现不得抛出越权异常，越权由调用方决定如何响应
     */
    boolean canRead(UserContext user, Long ownerId, Long ownerDeptId);
}
