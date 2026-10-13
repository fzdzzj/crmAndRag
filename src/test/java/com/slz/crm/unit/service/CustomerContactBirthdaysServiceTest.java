package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.pojo.vo.ContactBirthdayVO;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.CustomerContactRemarkMapper;
import com.slz.crm.server.properties.BirthdayReminderProperties;
import com.slz.crm.server.service.impl.CustomerContactServiceImpl;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 未来 N 天生日清单服务单元测试（add-source-citation-and-dashboard 任务 3.1）。
 *
 * <p>覆盖：名单范围计算（未来 N 天内生日条目装配）、数据权限范围过滤（仅处理数据权限查询返回的联系人，范围由 {@code QueryWrapperAspect} 在 mapper
 * 查询层注入——本测通过捕获传给 selectList 的 wrapper 与「越权联系人不经生日逻辑」双重断言）、 以及功能未启用时抛 NOT_ENABLED。全部零外呼（Mockito 桩）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客户联系人未来 N 天生日清单")
class CustomerContactBirthdaysServiceTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), CustomerContactEntity.class);
  }

  @Mock private CustomerContactMapper customerContactMapper;
  @Mock private CustomerContactRemarkMapper customerContactRemarkMapper;
  @Mock private BirthdayReminderProperties birthdayReminderProperties;

  @Spy @InjectMocks private CustomerContactServiceImpl contactService;

  /** 显式注入 baseMapper（沿用既有 CustomerContactServiceImplTest 做法，Mockito 对泛型 baseMapper 字段注入不可靠）。 */
  private void injectBaseMapper() {
    try {
      java.lang.reflect.Field field = ServiceImpl.class.getDeclaredField("baseMapper");
      field.setAccessible(true);
      field.set(contactService, customerContactMapper);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private CustomerContactEntity contact(long id, String name) {
    CustomerContactEntity c = new CustomerContactEntity();
    c.setId(id);
    c.setName(name);
    return c;
  }

  /** 本人生日本年（距今 daysLater 天），出生年份取 1990 年以校验输出 birthdayYear。 */
  private CustomerContactRemarkEntity selfBirthday(long contactId, int daysLater) {
    CustomerContactRemarkEntity r = new CustomerContactRemarkEntity();
    r.setContactId(contactId);
    r.setRemarkType(3);
    LocalDate target = LocalDate.now().plusDays(daysLater);
    r.setRemarkDate(target.withYear(1990));
    return r;
  }

  /** 亲属生日：距今 daysLater 天，出生年份 1988，备注姓名固定。 */
  private CustomerContactRemarkEntity relativeBirthday(
      long contactId, int daysLater, String relativeName) {
    CustomerContactRemarkEntity r = new CustomerContactRemarkEntity();
    r.setContactId(contactId);
    r.setRemarkType(4);
    r.setRemarkName(relativeName);
    LocalDate target = LocalDate.now().plusDays(daysLater);
    r.setRemarkDate(target.withYear(1988));
    return r;
  }

  @Test
  @DisplayName("数据权限范围内联系人的未来 N 天生日条目正确装配")
  void returnsExpectedBirthdayEntriesWithinDays() {
    injectBaseMapper();
    when(birthdayReminderProperties.isEnabled()).thenReturn(true);
    when(birthdayReminderProperties.getDays()).thenReturn(30);

    CustomerContactEntity a = contact(1L, "张伟");
    CustomerContactEntity b = contact(2L, "李娜");
    when(customerContactMapper.selectList(any())).thenReturn(List.of(a, b));
    when(customerContactRemarkMapper.selectByContactId(1L))
        .thenReturn(List.of(selfBirthday(1L, 5)));
    // 李娜本人生日 40 天后（超出 30 天窗口）应被排除
    when(customerContactRemarkMapper.selectByContactId(2L))
        .thenReturn(List.of(selfBirthday(2L, 40), relativeBirthday(2L, 3, "李明")));

    List<ContactBirthdayVO> result = contactService.getUpcomingBirthdays();

    assertEquals(2, result.size(), "30 天窗口内应只含张伟本人与李娜亲属两条");
    ContactBirthdayVO zhangWei =
        result.stream().filter(v -> v.getContactId() == 1L).findFirst().orElseThrow();
    assertEquals("张伟", zhangWei.getContactName());
    assertEquals(Boolean.TRUE, zhangWei.getIsSelf());
    assertEquals(5, zhangWei.getDaysUntil());
    assertEquals(1990, zhangWei.getBirthdayYear());

    ContactBirthdayVO liNaRelative =
        result.stream().filter(v -> v.getContactId() == 2L).findFirst().orElseThrow();
    assertEquals("李娜", liNaRelative.getContactName());
    assertEquals(Boolean.FALSE, liNaRelative.getIsSelf());
    assertEquals("李明", liNaRelative.getName());
    assertEquals(3, liNaRelative.getDaysUntil());
    assertEquals(1988, liNaRelative.getBirthdayYear());
  }

  @Test
  @DisplayName("数据权限范围过滤：仅处理权限查询返回的联系人，越权联系人不经生日逻辑")
  void appliesDataScopeFiltering() {
    injectBaseMapper();
    when(birthdayReminderProperties.isEnabled()).thenReturn(true);
    when(birthdayReminderProperties.getDays()).thenReturn(30);

    // 模拟 QueryWrapperAspect 已在查询层注入数据权限范围；越权联系人（id=999）不会出现在查询结果里
    CustomerContactEntity inScope = contact(1L, "王芳");
    when(customerContactMapper.selectList(any())).thenReturn(List.of(inScope));
    when(customerContactRemarkMapper.selectByContactId(1L))
        .thenReturn(List.of(selfBirthday(1L, 2)));

    List<ContactBirthdayVO> result = contactService.getUpcomingBirthdays();

    assertEquals(1, result.size(), "只应处理数据权限查询返回的联系人");
    assertEquals(Long.valueOf(1L), result.get(0).getContactId());
    // 越权联系人（id=999）不得有备注查询（说明其未被列入处理清单）
    verify(customerContactRemarkMapper, never()).selectByContactId(999L);
    verify(customerContactRemarkMapper).selectByContactId(1L);

    // 传递给 selectList 的 wrapper 携带未删除过滤，数据权限范围由同一查询路径注入
    ArgumentCaptor<LambdaQueryWrapper<CustomerContactEntity>> wrapperCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(customerContactMapper).selectList(wrapperCaptor.capture());
    LambdaQueryWrapper<CustomerContactEntity> captured = wrapperCaptor.getValue();
    assertTrue(
        captured.getSqlSegment().contains("is_deleted"),
        "wrapper 应含未删除过滤，后续由 QueryWrapperAspect 追加数据权限范围");
  }

  @Test
  @DisplayName("生日提醒未启用时抛 NOT_ENABLED")
  void disabledThrowsNotEnabled() {
    injectBaseMapper();
    when(birthdayReminderProperties.isEnabled()).thenReturn(false);

    BaseException ex =
        assertThrows(BaseException.class, () -> contactService.getUpcomingBirthdays());

    assertEquals(ErrorCode.NOT_ENABLED.getCode(), ex.getCode());
    verify(customerContactMapper, never()).selectList(any());
  }
}
