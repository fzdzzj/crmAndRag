package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.UserHandoverDTO;
import com.slz.crm.pojo.dto.UserHandoverQueryDTO;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.entity.UserHandoverEntity;
import com.slz.crm.pojo.vo.UserHandoverVO;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserHandoverMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.UserHandoverServiceImpl;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 用户交接分页 VO 转换单测（fix-engineering-debt-nplus1-loop-clone 任务 1.2）。
 *
 * <p>锁定 A-7：分页路径页内 from/to/operator 三类用户 ID 去重后单次 {@code selectBatchIds} 回填姓名（ {@code selectById}
 * 零调用），单条交接路径（executeHandover → buildHandoverVO）保持逐条取姓名不变。
 */
@DisplayName("用户交接分页用户名批量回填")
@ExtendWith(MockitoExtension.class)
class UserHandoverServiceImplTest {

  @Mock private UserHandoverMapper handoverMapper;

  @Mock private UserMapper userMapper;

  @Mock private ContactTaskMapper contactTaskMapper;

  @Mock private CustomerCompanyMapper customerCompanyMapper;

  @Mock private SalesOpportunityMapper salesOpportunityMapper;

  @InjectMocks private UserHandoverServiceImpl service;

  private static final long OPERATOR_ID = 1L;

  /** 纯 Mockito 无 MyBatis-Plus 启动流程，queryHandoverRecords 的 lambda 列名解析依赖实体 TableInfo。 */
  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    for (Class<?> entity :
        new Class<?>[] {
          UserHandoverEntity.class,
          ContactTaskEntity.class,
          CustomerCompanyEntity.class,
          SalesOpportunityEntity.class
        }) {
      TableInfoHelper.initTableInfo(assistant, entity);
    }
  }

  @BeforeEach
  void setUp() {
    RoleAO current = new RoleAO();
    current.setId(OPERATOR_ID);
    BaseUnit.setCurrentRole(current);
    // ServiceImpl.page(...) 走 baseMapper，显式绑定与 @InjectMocks 等价，避免注入歧义
    ReflectionTestUtils.setField(service, "baseMapper", handoverMapper);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  private UserHandoverEntity handover(Long id, Long fromId, Long toId, Long operatorId) {
    UserHandoverEntity entity = new UserHandoverEntity();
    entity.setId(id);
    entity.setFromUserId(fromId);
    entity.setToUserId(toId);
    entity.setOperatorId(operatorId);
    entity.setTaskCount(1);
    entity.setCustomerCount(2);
    entity.setOpportunityCount(3);
    return entity;
  }

  private UserEntity user(Long id, String realName) {
    UserEntity entity = new UserEntity();
    entity.setId(id);
    entity.setRealName(realName);
    return entity;
  }

  @Test
  @DisplayName("分页路径：selectBatchIds 恰一次、selectById 零调用、VO 三名字正确回填")
  void queryHandoverRecords_batchLoadsUserNames() {
    // 两行记录共用 from/to/operator 三类 ID，共 3 个去重后的用户（10/20/30）
    List<UserHandoverEntity> records =
        List.of(handover(1L, 10L, 20L, 30L), handover(2L, 10L, 20L, 30L));
    doAnswer(
            inv -> {
              Page<UserHandoverEntity> page = inv.getArgument(0);
              page.setTotal(records.size());
              page.setRecords(records);
              return page;
            })
        .when(handoverMapper)
        .selectPage(any(), any());
    when(userMapper.selectBatchIds(any()))
        .thenReturn(List.of(user(10L, "张三"), user(20L, "李四"), user(30L, "王五")));

    Page<UserHandoverVO> voPage = service.queryHandoverRecords(new UserHandoverQueryDTO());

    assertEquals(2, voPage.getRecords().size());
    for (UserHandoverVO vo : voPage.getRecords()) {
      assertEquals("张三", vo.getFromUserName());
      assertEquals("李四", vo.getToUserName());
      assertEquals("王五", vo.getOperatorName());
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
    verify(userMapper, times(1)).selectBatchIds(ids.capture());
    assertEquals(3, ids.getValue().size(), "页内三类用户 ID 必须去重后一次批量查询");
    assertTrue(ids.getValue().containsAll(List.of(10L, 20L, 30L)));

    // A-7 核心断言：分页路径不再有逐行 selectById（旧实现每行 3 次）
    verify(userMapper, never()).selectById(anyLong());
  }

  @Test
  @DisplayName("分页路径：用户被删除（批量结果缺失）时姓名回填 null，且空页零查询")
  void queryHandoverRecords_missingUserYieldsNullNames() {
    doAnswer(
            inv -> {
              Page<UserHandoverEntity> page = inv.getArgument(0);
              page.setTotal(1);
              page.setRecords(List.of(handover(1L, 10L, 20L, 30L)));
              return page;
            })
        .when(handoverMapper)
        .selectPage(any(), any());
    when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(10L, "张三")));

    Page<UserHandoverVO> voPage = service.queryHandoverRecords(new UserHandoverQueryDTO());

    UserHandoverVO vo = voPage.getRecords().get(0);
    assertEquals("张三", vo.getFromUserName());
    assertNull(vo.getToUserName());
    assertNull(vo.getOperatorName());

    doAnswer(
            inv -> {
              Page<UserHandoverEntity> page = inv.getArgument(0);
              page.setTotal(0);
              page.setRecords(List.of());
              return page;
            })
        .when(handoverMapper)
        .selectPage(any(), any());

    Page<UserHandoverVO> emptyPage = service.queryHandoverRecords(new UserHandoverQueryDTO());
    assertTrue(emptyPage.getRecords().isEmpty());
    verify(userMapper, times(1)).selectBatchIds(any());
    verify(userMapper, never()).selectById(anyLong());
  }

  @Test
  @DisplayName("单条路径回归：executeHandover 仍逐条取姓名（selectById 5 次、无批量查询）")
  void executeHandover_keepsPerUserLookup() {
    when(userMapper.selectById(10L)).thenReturn(userWithStatus(10L, "张三", 1));
    when(userMapper.selectById(20L)).thenReturn(userWithStatus(20L, "李四", 1));
    when(userMapper.selectById(OPERATOR_ID)).thenReturn(userWithStatus(OPERATOR_ID, "王五", 1));
    when(contactTaskMapper.update(any(), any())).thenReturn(2);
    when(customerCompanyMapper.update(any(), any())).thenReturn(1);
    when(salesOpportunityMapper.update(any(), any())).thenReturn(3);
    when(handoverMapper.insert(any())).thenReturn(1);

    UserHandoverDTO dto = new UserHandoverDTO();
    dto.setFromUserId(10L);
    dto.setToUserId(20L);

    UserHandoverVO vo = service.executeHandover(dto);

    assertEquals("张三", vo.getFromUserName());
    assertEquals("李四", vo.getToUserName());
    assertEquals("王五", vo.getOperatorName());
    assertEquals(2, vo.getTaskCount());
    // 校验 from/to 各 1 次 + buildHandoverVO 的 from/to/operator 各 1 次
    verify(userMapper, times(5)).selectById(anyLong());
    verify(userMapper, never()).selectBatchIds(any());
  }

  private UserEntity userWithStatus(Long id, String realName, Integer status) {
    UserEntity entity = user(id, realName);
    entity.setStatus(status);
    return entity;
  }
}
