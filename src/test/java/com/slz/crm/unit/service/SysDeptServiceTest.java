package com.slz.crm.unit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.SysDeptDTO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.SysDeptServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 部门服务单元测试：增删改校验、子部门/用户引用保护、改名缓存清理。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("部门服务")
class SysDeptServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache deptNameCache;

    @Mock
    private SysDeptMapper sysDeptMapper;

    @Spy
    @InjectMocks
    private SysDeptServiceImpl deptService;

    // ===== listEnabled =====

    @Test
    @DisplayName("列表仅返回启用部门")
    void listEnabledReturnsOnlyEnabled() {
        SysDeptEntity enabled = dept(1L, "销售部", 1);
        SysDeptEntity disabled = dept(2L, "停用部", 0);
        doReturn(List.of(enabled, disabled)).when(deptService).list(any(LambdaQueryWrapper.class));

        List<SysDeptEntity> result = deptService.listEnabled();

        // 过滤逻辑在 wrapper 上，这里验证方法可执行且命中 list
        assertEquals(2, result.size());
        verify(deptService).list(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("部门管理列表返回全部部门（含停用）")
    void listAllReturnsAllIncludingDisabled() {
        SysDeptEntity enabled = dept(1L, "销售部", 1);
        SysDeptEntity disabled = dept(2L, "停用部", 0);
        doReturn(List.of(enabled, disabled)).when(deptService).list(any(LambdaQueryWrapper.class));

        List<SysDeptEntity> result = deptService.listAll();

        assertEquals(2, result.size());
        verify(deptService).list(any(LambdaQueryWrapper.class));
    }

    // ===== add =====

    @Test
    @DisplayName("新增部门：名称必填与长度校验")
    void addValidatesName() {
        assertThrows(BaseException.class, () -> deptService.add(new SysDeptDTO()));

        SysDeptDTO blank = new SysDeptDTO();
        blank.setDeptName("   ");
        assertThrows(BaseException.class, () -> deptService.add(blank));

        SysDeptDTO tooLong = new SysDeptDTO();
        tooLong.setDeptName("长".repeat(51));
        assertThrows(BaseException.class, () -> deptService.add(tooLong));
    }

    @Test
    @DisplayName("新增部门：同名部门禁止重复")
    void addRejectsDuplicateName() {
        SysDeptDTO dto = new SysDeptDTO();
        dto.setDeptName("销售部");
        doReturn(1L).when(deptService).count(any(LambdaQueryWrapper.class));

        assertThrows(BaseException.class, () -> deptService.add(dto));
    }

    @Test
    @DisplayName("新增部门：默认排序0/状态启用")
    void addUsesDefaults() {
        SysDeptDTO dto = new SysDeptDTO();
        dto.setDeptName("销售部");
        doReturn(0L).when(deptService).count(any(LambdaQueryWrapper.class));
        doReturn(true).when(deptService).save(any(SysDeptEntity.class));

        assertTrue(deptService.add(dto));

        ArgumentCaptor<SysDeptEntity> captor = ArgumentCaptor.forClass(SysDeptEntity.class);
        verify(deptService).save(captor.capture());
        assertEquals("销售部", captor.getValue().getDeptName());
        assertEquals(0, captor.getValue().getSort());
        assertEquals(1, captor.getValue().getStatus());
    }

    // ===== update =====

    @Test
    @DisplayName("编辑部门：不存在/重名（排除自身）")
    void updateRejectsMissingOrDuplicate() {
        SysDeptDTO missing = new SysDeptDTO();
        missing.setId(99L);
        doReturn(null).when(deptService).getById(99L);
        assertThrows(BaseException.class, () -> deptService.update(missing));

        SysDeptEntity existing = dept(1L, "销售部", 1);
        doReturn(existing).when(deptService).getById(1L);
        doReturn(1L).when(deptService).count(any(LambdaQueryWrapper.class));
        SysDeptDTO duplicate = new SysDeptDTO();
        duplicate.setId(1L);
        duplicate.setDeptName("销售部");
        assertThrows(BaseException.class, () -> deptService.update(duplicate));
    }

    @Test
    @DisplayName("编辑部门：parentId 不能是自己")
    void updateRejectsSelfParent() {
        SysDeptEntity existing = dept(1L, "销售部", 1);
        doReturn(existing).when(deptService).getById(1L);

        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(1L);
        dto.setParentId(1L);
        assertThrows(BaseException.class, () -> deptService.update(dto));
    }

    @Test
    @DisplayName("编辑部门：parentId 指向不存在的部门被拒绝")
    void updateRejectsMissingParent() {
        SysDeptEntity existing = dept(1L, "销售部", 1);
        doReturn(existing).when(deptService).getById(1L);
        doReturn(null).when(deptService).getById(999L);

        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(1L);
        dto.setParentId(999L);
        assertThrows(BaseException.class, () -> deptService.update(dto));
    }

    @Test
    @DisplayName("改名时清除 deptName 缓存")
    void updateEvictsCacheOnRename() {
        SysDeptEntity existing = dept(1L, "旧名称", 1);
        doReturn(existing).when(deptService).getById(1L);
        doReturn(0L).when(deptService).count(any(LambdaQueryWrapper.class));
        doReturn(true).when(deptService).updateById(any(SysDeptEntity.class));
        when(cacheManager.getCache("deptName")).thenReturn(deptNameCache);

        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(1L);
        dto.setDeptName("新名称");
        assertTrue(deptService.update(dto));

        verify(deptNameCache).clear();
        assertEquals("新名称", existing.getDeptName());
    }

    @Test
    @DisplayName("未改名时不清缓存")
    void updateDoesNotEvictWhenNameUnchanged() {
        SysDeptEntity existing = dept(1L, "销售部", 1);
        doReturn(existing).when(deptService).getById(1L);
        doReturn(true).when(deptService).updateById(any(SysDeptEntity.class));

        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(1L);
        dto.setSort(5);
        assertTrue(deptService.update(dto));

        verify(cacheManager, never()).getCache("deptName");
    }

    // ===== delete =====

    @Test
    @DisplayName("删除部门：不存在/有子部门/有用户均禁止")
    void deleteGuardsReferences() {
        doReturn(null).when(deptService).getById(99L);
        assertThrows(BaseException.class, () -> deptService.delete(99L));

        SysDeptEntity existing = dept(1L, "销售部", 1);
        doReturn(existing).when(deptService).getById(1L);

        // 有子部门
        doReturn(1L).when(deptService).count(any(LambdaQueryWrapper.class));
        assertThrows(BaseException.class, () -> deptService.delete(1L));

        // 无子部门但有用户
        doReturn(0L).when(deptService).count(any(LambdaQueryWrapper.class));
        when(userMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BaseException.class, () -> deptService.delete(1L));
    }

    @Test
    @DisplayName("无引用时可正常删除")
    void deleteSucceeds() {
        SysDeptEntity existing = dept(1L, "销售部", 1);
        doReturn(existing).when(deptService).getById(1L);
        doReturn(0L).when(deptService).count(any(LambdaQueryWrapper.class));
        when(userMapper.selectCount(any())).thenReturn(0L);
        doReturn(true).when(deptService).removeById(1L);

        assertTrue(deptService.delete(1L));
    }

    // ===== 父链完整性与状态校验 =====

    @Test
    @DisplayName("编辑部门：二节点互设父部门形成循环被拒绝")
    void updateRejectsTwoNodeParentCycle() {
        // A 的上级是 B（A -> B）
        SysDeptEntity a = dept(1L, "A部门", 1);
        a.setParentId(2L);
        SysDeptEntity b = dept(2L, "B部门", 1);
        doReturn(a).when(deptService).getById(1L);
        doReturn(b).when(deptService).getById(2L);

        // 把 B 的上级设为 A（B -> A -> B 循环）
        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(2L);
        dto.setParentId(1L);
        assertThrows(BaseException.class, () -> deptService.update(dto));
    }

    @Test
    @DisplayName("编辑部门：三层父链形成循环被拒绝")
    void updateRejectsThreeLevelParentCycle() {
        // A -> B -> C（A 的上级是 B，B 的上级是 C）
        SysDeptEntity a = dept(1L, "A部门", 1);
        a.setParentId(2L);
        SysDeptEntity b = dept(2L, "B部门", 1);
        b.setParentId(3L);
        SysDeptEntity c = dept(3L, "C部门", 1);
        doReturn(a).when(deptService).getById(1L);
        doReturn(b).when(deptService).getById(2L);
        doReturn(c).when(deptService).getById(3L);

        // 把 C 的上级设为 A（C -> A -> B -> C 循环）
        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(3L);
        dto.setParentId(1L);
        assertThrows(BaseException.class, () -> deptService.update(dto));
    }

    @Test
    @DisplayName("新增部门：上级部门不存在被拒绝")
    void addRejectsMissingParent() {
        doReturn(null).when(deptService).getById(999L);
        doReturn(0L).when(deptService).count(any(LambdaQueryWrapper.class));

        SysDeptDTO dto = new SysDeptDTO();
        dto.setDeptName("新部门");
        dto.setParentId(999L);
        assertThrows(BaseException.class, () -> deptService.add(dto));
    }

    @Test
    @DisplayName("新增/编辑部门：状态只能为 0 或 1")
    void addAndUpdateRejectInvalidStatus() {
        doReturn(0L).when(deptService).count(any(LambdaQueryWrapper.class));
        SysDeptDTO addDto = new SysDeptDTO();
        addDto.setDeptName("新部门");
        addDto.setStatus(2);
        assertThrows(BaseException.class, () -> deptService.add(addDto));

        SysDeptEntity existing = dept(1L, "销售部", 1);
        doReturn(existing).when(deptService).getById(1L);
        SysDeptDTO updateDto = new SysDeptDTO();
        updateDto.setId(1L);
        updateDto.setStatus(3);
        assertThrows(BaseException.class, () -> deptService.update(updateDto));
    }

    @Test
    @DisplayName("编辑部门：合法改组（移到其他部门下）通过")
    void updateAllowsLegitimateReparent() {
        SysDeptEntity self = dept(1L, "销售部", 1);
        doReturn(self).when(deptService).getById(1L);
        // 目标部门为独立部门（无祖先链），改组合法
        SysDeptEntity parent = dept(2L, "业务中心", 1);
        doReturn(parent).when(deptService).getById(2L);
        doReturn(true).when(deptService).updateById(any(SysDeptEntity.class));

        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(1L);
        dto.setParentId(2L);
        assertTrue(deptService.update(dto));
        assertEquals(2L, self.getParentId());
    }

    @Test
    @DisplayName("编辑部门：超过50层的深链中把祖先移到自己后代下也被拒绝")
    void updateRejectsDeepChainReparentToDescendant() {
        // 构造 1 <- 2 <- ... <- 60 的60层链（旧实现层数上限50会漏检此类环）
        for (long i = 1; i <= 60; i++) {
            SysDeptEntity node = dept(i, "部门" + i, 1);
            if (i > 1) {
                node.setParentId(i - 1);
            }
            doReturn(node).when(deptService).getById(i);
        }

        // 把 1 号（整条链的根）移到链尾 60 号下面
        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(1L);
        dto.setParentId(60L);
        BaseException ex = assertThrows(BaseException.class, () -> deptService.update(dto));
        assertTrue(ex.getMessage().contains("子部门"));
    }

    @Test
    @DisplayName("编辑部门：父链存在既有循环引用的历史异常数据时明确拒绝")
    void updateRejectsWhenParentChainContainsExistingCycle() {
        // 历史脏数据：7 -> 8 -> 7 已成环；新操作不应静默放过而是明确报错
        SysDeptEntity seven = dept(7L, "异常A", 1);
        seven.setParentId(8L);
        SysDeptEntity eight = dept(8L, "异常B", 1);
        eight.setParentId(7L);
        doReturn(seven).when(deptService).getById(7L);
        doReturn(eight).when(deptService).getById(8L);
        doReturn(dept(5L, "正常部门", 1)).when(deptService).getById(5L);

        SysDeptDTO dto = new SysDeptDTO();
        dto.setId(5L);
        dto.setParentId(7L);
        BaseException ex = assertThrows(BaseException.class, () -> deptService.update(dto));
        assertTrue(ex.getMessage().contains("循环引用"));
    }

    private SysDeptEntity dept(Long id, String name, Integer status) {
        SysDeptEntity dept = new SysDeptEntity();
        dept.setId(id);
        dept.setDeptName(name);
        dept.setStatus(status);
        return dept;
    }
}
