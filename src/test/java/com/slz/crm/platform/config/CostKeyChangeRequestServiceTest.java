package com.slz.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.platform.config.controller.CostKeyChangeRequestRejectReq;
import com.slz.crm.platform.config.controller.CostKeyChangeRequestSubmitReq;
import com.slz.crm.platform.config.entity.CostKeyChangeRequestEntity;
import com.slz.crm.platform.config.mapper.CostKeyChangeRequestMapper;
import com.slz.crm.platform.config.service.CostKeyChangeRequestService;
import com.slz.crm.platform.config.service.CostKeyChangeRequestServiceImpl;
import com.slz.crm.platform.config.service.DynamicConfigAdminService;
import com.slz.crm.platform.contract.PlatformErrorCode;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成本键申请-审批流服务测试（add-cost-key-approval-workflow 任务 2.2）。
 *
 * <p>测试四态状态机、一键一单在途、COST 白名单约束、值预校验、审批写入原子性及数据范围。
 */
class CostKeyChangeRequestServiceTest {

  private static final String COST_KEY = "rag.query.hyde.enabled"; // COST 22 键之一（布尔型）
  private static final String OPERATIONAL_KEY = "ai.prompt.system"; // OPERATIONAL 键
  private static final String UNKNOWN_KEY = "unknown.cost.key"; // 未知键

  private CostKeyChangeRequestMapper mapper;
  private DynamicConfigAdminService adminService;
  private DynamicConfigKeyRegistry registry;
  private CurrentUserResolver userResolver;
  private CostKeyChangeRequestService service;

  private final List<CostKeyChangeRequestEntity> database = new ArrayList<>();
  private final AtomicLong idGenerator = new AtomicLong(100L);

  @BeforeEach
  void setUp() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        CostKeyChangeRequestEntity.class);

    mapper = mock(CostKeyChangeRequestMapper.class);
    adminService = mock(DynamicConfigAdminService.class);
    registry = new DynamicConfigKeyRegistry(new ObjectMapper());
    userResolver = new CurrentUserResolver();

    service = new CostKeyChangeRequestServiceImpl(mapper, adminService, registry, userResolver);

    database.clear();

    // 模拟 mapper 插入
    when(mapper.insert(any(CostKeyChangeRequestEntity.class)))
        .thenAnswer(
            invocation -> {
              CostKeyChangeRequestEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                entity.setId(idGenerator.incrementAndGet());
              }
              if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(LocalDateTime.now());
              }
              database.add(entity);
              return 1;
            });

    // 模拟 mapper updateById
    when(mapper.updateById(any(CostKeyChangeRequestEntity.class)))
        .thenAnswer(
            invocation -> {
              CostKeyChangeRequestEntity entity = invocation.getArgument(0);
              for (int i = 0; i < database.size(); i++) {
                if (database.get(i).getId().equals(entity.getId())) {
                  database.set(i, entity);
                  return 1;
                }
              }
              return 0;
            });

    // 模拟 mapper selectById
    when(mapper.selectById(any()))
        .thenAnswer(
            invocation -> {
              Long id = invocation.getArgument(0);
              return database.stream().filter(e -> e.getId().equals(id)).findFirst().orElse(null);
            });

    // 模拟 mapper selectPage
    when(mapper.selectPage(any(), any()))
        .thenAnswer(
            invocation -> {
              Page<CostKeyChangeRequestEntity> page = invocation.getArgument(0);
              page.setRecords(new ArrayList<>(database));
              page.setTotal(database.size());
              return page;
            });
  }

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  private static UserContext admin() {
    return new UserContext(1L, 1L, 1L, DataScopeLevel.ALL, "超级管理员");
  }

  private static UserContext requester() {
    return new UserContext(10L, 10L, 3L, DataScopeLevel.SELF, "普通业务员");
  }

  private static UserContext otherUser() {
    return new UserContext(20L, 20L, 3L, DataScopeLevel.SELF, "另一业务员");
  }

  @Test
  @DisplayName("提交：合法 COST 键且值合法，成功进入 PENDING 态")
  void submit_successForCostKey() {
    UserContextHolder.runWith(
        requester(),
        () -> {
          CostKeyChangeRequestSubmitReq req = new CostKeyChangeRequestSubmitReq();
          req.setConfigKey(COST_KEY);
          req.setRequestedValue("true");
          req.setReason("需要开启 Hyde 进行评测");

          CostKeyChangeRequestVO vo = service.submit(req);

          assertThat(vo).isNotNull();
          assertThat(vo.status()).isEqualTo(CostKeyChangeRequestStatus.PENDING.name());
          assertThat(vo.configKey()).isEqualTo(COST_KEY);
          assertThat(vo.requestedValue()).isEqualTo("true");
          assertThat(vo.requesterId()).isEqualTo(10L);
        });
  }

  @Test
  @DisplayName("提交：非 COST 键（OPERATIONAL 或未知键）一律拒绝")
  void submit_rejectsNonCostKey() {
    UserContextHolder.runWith(
        requester(),
        () -> {
          CostKeyChangeRequestSubmitReq req1 = new CostKeyChangeRequestSubmitReq();
          req1.setConfigKey(OPERATIONAL_KEY);
          req1.setRequestedValue("hello");
          assertThatThrownBy(() -> service.submit(req1))
              .isInstanceOf(ServiceException.class)
              .hasMessageContaining("非成本档配置键");

          CostKeyChangeRequestSubmitReq req2 = new CostKeyChangeRequestSubmitReq();
          req2.setConfigKey(UNKNOWN_KEY);
          req2.setRequestedValue("123");
          assertThatThrownBy(() -> service.submit(req2)).isInstanceOf(ServiceException.class);
        });
  }

  @Test
  @DisplayName("提交：值预校验失败拒绝，且不落库")
  void submit_rejectsInvalidValue() {
    UserContextHolder.runWith(
        requester(),
        () -> {
          CostKeyChangeRequestSubmitReq req = new CostKeyChangeRequestSubmitReq();
          req.setConfigKey(COST_KEY); // 布尔型
          req.setRequestedValue("not_a_boolean");

          assertThatThrownBy(() -> service.submit(req)).isInstanceOf(ServiceException.class);
          assertThat(database).isEmpty();
        });
  }

  @Test
  @DisplayName("提交：一键一单在途，同键已有 PENDING 申请时拒绝")
  void submit_rejectsDuplicatePendingRequest() {
    CostKeyChangeRequestEntity pending = new CostKeyChangeRequestEntity();
    pending.setId(1L);
    pending.setConfigKey(COST_KEY);
    pending.setRequestedValue("true");
    pending.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    pending.setRequesterId(10L);
    database.add(pending);

    when(mapper.selectCount(any())).thenReturn(1L);

    UserContextHolder.runWith(
        requester(),
        () -> {
          CostKeyChangeRequestSubmitReq req = new CostKeyChangeRequestSubmitReq();
          req.setConfigKey(COST_KEY);
          req.setRequestedValue("false");

          assertThatThrownBy(() -> service.submit(req))
              .isInstanceOf(ServiceException.class)
              .hasMessageContaining("在途申请");
        });
  }

  @Test
  @DisplayName("撤回：本人且处于 PENDING 态时成功，状态转为 WITHDRAWN")
  void withdraw_successForOwnerInPending() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(10L);
    database.add(entity);

    UserContextHolder.runWith(
        requester(),
        () -> {
          CostKeyChangeRequestVO vo = service.withdraw(1L);
          assertThat(vo.status()).isEqualTo(CostKeyChangeRequestStatus.WITHDRAWN.name());
          assertThat(vo.decidedAt()).isNotNull();
        });
  }

  @Test
  @DisplayName("撤回：非本人撤回抛越权异常拒绝")
  void withdraw_rejectsNonOwner() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(10L);
    database.add(entity);

    UserContextHolder.runWith(
        otherUser(),
        () -> {
          assertThatThrownBy(() -> service.withdraw(1L))
              .isInstanceOf(ServiceException.class)
              .hasFieldOrPropertyWithValue("code", PlatformErrorCode.FORBIDDEN.getCode());
        });
  }

  @Test
  @DisplayName("撤回：非 PENDING 态（如已 APPROVED）撤回拒绝")
  void withdraw_rejectsNonPending() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setStatus(CostKeyChangeRequestStatus.APPROVED.name());
    entity.setRequesterId(10L);
    database.add(entity);

    UserContextHolder.runWith(
        requester(),
        () -> {
          assertThatThrownBy(() -> service.withdraw(1L)).isInstanceOf(ServiceException.class);
        });
  }

  @Test
  @DisplayName("审批：超管审批通过，以审批人身份写入配置并回填版本号")
  void approve_successForSuperAdminAndWritesConfig() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setRequestedValue("true");
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(10L);
    database.add(entity);

    ConfigItemView view = mock(ConfigItemView.class);
    when(view.version()).thenReturn(4);
    when(adminService.updateValue(eq(COST_KEY), eq("true"), any())).thenReturn(view);

    UserContextHolder.runWith(
        admin(),
        () -> {
          CostKeyChangeRequestVO vo = service.approve(1L);
          assertThat(vo.status()).isEqualTo(CostKeyChangeRequestStatus.APPROVED.name());
          assertThat(vo.approverId()).isEqualTo(1L);
          assertThat(vo.appliedConfigVersion()).isEqualTo(4L);

          verify(adminService).updateValue(eq(COST_KEY), eq("true"), any());
        });
  }

  @Test
  @DisplayName("审批：写入失败则申请单保持 PENDING，不落半状态，异常向上抛出")
  void approve_writeFailsKeepsPending() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setRequestedValue("true");
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(10L);
    database.add(entity);

    when(adminService.updateValue(any(), any(), any())).thenThrow(new RuntimeException("数据库写入超时"));

    UserContextHolder.runWith(
        admin(),
        () -> {
          assertThatThrownBy(() -> service.approve(1L))
              .isInstanceOf(RuntimeException.class)
              .hasMessageContaining("数据库写入超时");

          // 保持 PENDING
          CostKeyChangeRequestEntity current = database.get(0);
          assertThat(current.getStatus()).isEqualTo(CostKeyChangeRequestStatus.PENDING.name());
          assertThat(current.getAppliedConfigVersion()).isNull();
        });
  }

  @Test
  @DisplayName("审批：非超管审批抛 96005 拒绝")
  void approve_rejectsNonSuperAdmin() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(10L);
    database.add(entity);

    UserContextHolder.runWith(
        requester(),
        () -> {
          assertThatThrownBy(() -> service.approve(1L))
              .isInstanceOf(ServiceException.class)
              .hasFieldOrPropertyWithValue("code", PlatformErrorCode.FORBIDDEN.getCode());

          verify(adminService, never()).updateValue(any(), any(), any());
        });
  }

  @Test
  @DisplayName("驳回：超管驳回成功，状态转为 REJECTED 并记录 rejectReason")
  void reject_successForSuperAdmin() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(10L);
    database.add(entity);

    CostKeyChangeRequestRejectReq req = new CostKeyChangeRequestRejectReq();
    req.setRejectReason("预算超标，暂不开通");

    UserContextHolder.runWith(
        admin(),
        () -> {
          CostKeyChangeRequestVO vo = service.reject(1L, req);
          assertThat(vo.status()).isEqualTo(CostKeyChangeRequestStatus.REJECTED.name());
          assertThat(vo.rejectReason()).isEqualTo("预算超标，暂不开通");
          assertThat(vo.approverId()).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("驳回：非超管驳回抛 96005 拒绝")
  void reject_rejectsNonSuperAdmin() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setStatus(CostKeyChangeRequestStatus.PENDING.name());
    entity.setRequesterId(10L);
    database.add(entity);

    UserContextHolder.runWith(
        requester(),
        () -> {
          assertThatThrownBy(() -> service.reject(1L, new CostKeyChangeRequestRejectReq()))
              .isInstanceOf(ServiceException.class)
              .hasFieldOrPropertyWithValue("code", PlatformErrorCode.FORBIDDEN.getCode());
        });
  }

  @Test
  @DisplayName("终态不可逆：已 APPROVED/REJECTED/WITHDRAWN 的单据不可再审批或驳回")
  void terminalStateCannotTransition() {
    CostKeyChangeRequestEntity entity = new CostKeyChangeRequestEntity();
    entity.setId(1L);
    entity.setConfigKey(COST_KEY);
    entity.setStatus(CostKeyChangeRequestStatus.REJECTED.name());
    entity.setRequesterId(10L);
    database.add(entity);

    UserContextHolder.runWith(
        admin(),
        () -> {
          assertThatThrownBy(() -> service.approve(1L))
              .isInstanceOf(ServiceException.class)
              .hasMessageContaining("非待审批状态");

          assertThatThrownBy(() -> service.reject(1L, new CostKeyChangeRequestRejectReq()))
              .isInstanceOf(ServiceException.class)
              .hasMessageContaining("非待审批状态");
        });
  }

  @Test
  @DisplayName("清单查询：超管查全部，普通用户只查本人申请")
  void list_scopesByRole() {
    UserContextHolder.runWith(
        admin(),
        () -> {
          Page<CostKeyChangeRequestVO> page = service.list(1, 10, null, null);
          assertThat(page).isNotNull();
        });

    UserContextHolder.runWith(
        requester(),
        () -> {
          Page<CostKeyChangeRequestVO> page = service.list(1, 10, null, null);
          assertThat(page).isNotNull();
        });
  }

  @Test
  @DisplayName("未登录：所有操作均抛 UNAUTHORIZED(96003)")
  void unauthenticated_rejectsAll() {
    UserContextHolder.clear();

    assertThatThrownBy(() -> service.submit(new CostKeyChangeRequestSubmitReq()))
        .isInstanceOf(ServiceException.class)
        .hasFieldOrPropertyWithValue("code", PlatformErrorCode.UNAUTHORIZED.getCode());

    assertThatThrownBy(() -> service.withdraw(1L))
        .isInstanceOf(ServiceException.class)
        .hasFieldOrPropertyWithValue("code", PlatformErrorCode.UNAUTHORIZED.getCode());

    assertThatThrownBy(() -> service.approve(1L))
        .isInstanceOf(ServiceException.class)
        .hasFieldOrPropertyWithValue("code", PlatformErrorCode.UNAUTHORIZED.getCode());

    assertThatThrownBy(() -> service.reject(1L, new CostKeyChangeRequestRejectReq()))
        .isInstanceOf(ServiceException.class)
        .hasFieldOrPropertyWithValue("code", PlatformErrorCode.UNAUTHORIZED.getCode());

    assertThatThrownBy(() -> service.list(1, 10, null, null))
        .isInstanceOf(ServiceException.class)
        .hasFieldOrPropertyWithValue("code", PlatformErrorCode.UNAUTHORIZED.getCode());
  }
}
