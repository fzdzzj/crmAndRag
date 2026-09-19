<template>
  <div class="w-full">
    <Card size="small">
      <template #title>
        <div class="flex flex-wrap items-center justify-between gap-2">
          <span class="font-medium">组织架构</span>
          <div class="flex items-center gap-2">
            <Input
              v-model:value="groupKeyword"
              allow-clear
              placeholder="搜索集团"
              style="width: 220px"
            />
            <Button type="primary" size="small" data-tour="org-create-group" @click="openGroupModal()">
              新增集团
            </Button>
          </div>
        </div>
      </template>

      <Spin :spinning="isGroupsLoading || isDeptsLoading">
        <Empty
          v-if="!isGroupsLoading && (groups ?? []).length === 0"
          description="暂无集团，点击右上角新增"
        />
        <a-collapse
          v-else
          v-model:active-key="activeKeys"
          expand-icon-position="end"
        >
          <a-collapse-panel
            v-for="group in groups ?? []"
            :key="group.id!"
          >
            <template #header>
              <span class="flex w-full items-center gap-2" @click.stop>
                <span class="font-medium">{{ group.groupName }}</span>
                <Tag :color="group.status === 1 ? 'green' : 'red'">
                  {{ group.status === 1 ? '启用' : '停用' }}
                </Tag>
                <span class="ml-auto flex items-center gap-2" @click.stop>
                  <Popover content="新增部门" placement="top">
                    <PlusOutlined
                      class="cursor-pointer text-blue-500 hover:text-blue-700"
                      @click="openDeptModal(undefined, group)"
                    />
                  </Popover>
                  <Popover content="编辑集团" placement="top">
                    <EditOutlined
                      class="cursor-pointer text-green-500 hover:text-green-700"
                      @click="openGroupModal(group)"
                    />
                  </Popover>
                  <Popover
                    :content="group.status === 1 ? '停用集团' : '启用集团'"
                    placement="top"
                  >
                    <PoweroffOutlined
                      class="cursor-pointer text-amber-500 hover:text-amber-700"
                      @click="handleToggleGroup(group)"
                    />
                  </Popover>
                  <Popover content="删除集团" placement="top">
                    <DeleteOutlined
                      class="cursor-pointer text-red-500 hover:text-red-700"
                      @click="handleDeleteGroup(group)"
                    />
                  </Popover>
                </span>
              </span>
            </template>

            <div class="py-1">
              <div class="mb-2 flex items-center justify-between">
                <span class="text-sm text-gray-500">
                  部门（{{ deptsByGroup.get(group.id!)?.length ?? 0 }}）
                </span>
                <Button size="small" @click="openDeptModal(undefined, group)">
                  新增部门
                </Button>
              </div>
              <Table
                size="small"
                :scroll="{ x: 'max-content' }"
                :columns="deptColumns"
                :data-source="deptsByGroup.get(group.id!) ?? []"
                :pagination="false"
                :row-key="(record: OrgDept) => record.id!"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'status'">
                    <Tag :color="(record as OrgDept).status === 1 ? 'green' : 'red'">
                      {{ (record as OrgDept).status === 1 ? '启用' : '停用' }}
                    </Tag>
                  </template>
                  <template v-else-if="column.key === 'action'">
                    <span class="whitespace-nowrap">
                      <Popover content="编辑部门" placement="top">
                        <EditOutlined
                          class="mr-2 cursor-pointer text-green-500 hover:text-green-700"
                          @click="openDeptModal(record as OrgDept, group)"
                        />
                      </Popover>
                      <Popover
                        :content="(record as OrgDept).status === 1 ? '停用部门' : '启用部门'"
                        placement="top"
                      >
                        <PoweroffOutlined
                          class="mr-2 cursor-pointer text-amber-500 hover:text-amber-700"
                          @click="handleToggleDept(record as OrgDept)"
                        />
                      </Popover>
                      <Popover content="删除部门" placement="top">
                        <DeleteOutlined
                          class="cursor-pointer text-red-500 hover:text-red-700"
                          @click="handleDeleteDept(record as OrgDept)"
                        />
                      </Popover>
                    </span>
                  </template>
                </template>
              </Table>
              <Empty
                v-if="(deptsByGroup.get(group.id!) ?? []).length === 0"
                image="simple"
                description="暂无部门"
              />
            </div>
          </a-collapse-panel>
        </a-collapse>
      </Spin>
    </Card>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:open="modalOpen"
      :title="modalTitle"
      :confirm-loading="modalSaving"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleModalOk"
    >
      <a-form :label-col="{ span: 6 }" :wrapper-col="{ span: 18 }">
        <a-form-item v-if="modalType === 'group'" label="集团名称">
          <a-input
            v-model:value="modalName"
            placeholder="请输入集团名称"
            :maxlength="50"
            @press-enter="handleModalOk"
          />
        </a-form-item>
        <template v-else>
          <a-form-item label="所属集团">
            <a-input :value="modalGroupName" disabled />
          </a-form-item>
          <a-form-item label="部门名称">
            <a-input
              v-model:value="modalName"
              placeholder="请输入部门名称"
              :maxlength="50"
              @press-enter="handleModalOk"
            />
          </a-form-item>
        </template>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import {
  Button,
  Card,
  Collapse as ACollapse,
  CollapsePanel as ACollapsePanel,
  Empty,
  Form as AForm,
  FormItem as AFormItem,
  Input,
  Input as AInput,
  message,
  Modal,
  Modal as AModal,
  Popover,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';
import { computed, ref, watch } from 'vue';
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  PoweroffOutlined,
} from '@ant-design/icons-vue';
import {
  useOrgGroups,
  useAllOrgDepts,
  useCreateOrgGroup,
  useUpdateOrgGroup,
  useToggleOrgGroupStatus,
  useDeleteOrgGroup,
  useCreateOrgDept,
  useUpdateOrgDept,
  useToggleOrgDeptStatus,
  useDeleteOrgDept,
  type OrgGroup,
  type OrgDept,
} from '@/hooks/useOrgChart';

const groupKeyword = ref('');

const {
  data: groups,
  isLoading: isGroupsLoading,
} = useOrgGroups(groupKeyword);

const {
  data: allDepts,
  isLoading: isDeptsLoading,
} = useAllOrgDepts();

const deptsByGroup = computed(() => {
  const map = new Map<number, OrgDept[]>();
  for (const dept of allDepts.value ?? []) {
    const key = dept.groupId!;
    const list = map.get(key) ?? [];
    list.push(dept);
    map.set(key, list);
  }
  return map;
});

// 折叠面板：首次加载集团后默认全部展开
const activeKeys = ref<(string | number)[]>([]);
let activeKeysInitialized = false;
watch(groups, (list) => {
  if (!activeKeysInitialized && list && list.length > 0) {
    activeKeys.value = list.map((g) => g.id!);
    activeKeysInitialized = true;
  }
});

const deptColumns = [
  { title: '部门名称', dataIndex: 'deptName', key: 'deptName' },
  { title: '状态', dataIndex: 'status', key: 'status', width: 76 },
  { title: '操作', key: 'action', width: 96 },
];

// 新增/编辑弹窗
const modalOpen = ref(false);
const modalType = ref<'group' | 'dept'>('group');
const modalEditingId = ref<number | undefined>(undefined);
const modalGroupId = ref<number | undefined>(undefined);
const modalGroupName = ref('');
const modalName = ref('');
const modalSaving = ref(false);

const modalTitle = computed(() => {
  const isEdit = modalEditingId.value !== undefined;
  if (modalType.value === 'group') return isEdit ? '编辑集团' : '新增集团';
  return isEdit ? '编辑部门' : '新增部门';
});

function openGroupModal(record?: OrgGroup) {
  modalType.value = 'group';
  modalEditingId.value = record?.id;
  modalName.value = record?.groupName ?? '';
  modalOpen.value = true;
}

function openDeptModal(record: { id?: number; deptName?: string } | undefined, group: OrgGroup) {
  modalType.value = 'dept';
  modalEditingId.value = record?.id;
  modalGroupId.value = group.id;
  modalGroupName.value = group.groupName ?? '';
  modalName.value = record?.deptName ?? '';
  modalOpen.value = true;
}

const { mutateAsync: createGroup } = useCreateOrgGroup();
const { mutateAsync: updateGroup } = useUpdateOrgGroup();
const { mutateAsync: createDept } = useCreateOrgDept();
const { mutateAsync: updateDept } = useUpdateOrgDept();

async function handleModalOk() {
  const name = modalName.value.trim();
  if (!name) {
    message.warning('名称不能为空');
    return;
  }
  modalSaving.value = true;
  try {
    if (modalType.value === 'group') {
      if (modalEditingId.value !== undefined) {
        await updateGroup({ id: modalEditingId.value, groupName: name });
      } else {
        await createGroup({ groupName: name });
      }
    } else {
      if (!modalGroupId.value) return;
      if (modalEditingId.value !== undefined) {
        await updateDept({
          id: modalEditingId.value,
          groupId: modalGroupId.value,
          deptName: name,
        });
      } else {
        await createDept({ groupId: modalGroupId.value, deptName: name });
      }
    }
    modalOpen.value = false;
  } catch {
    // 错误提示已由 hooks 统一处理，弹窗保持打开
  } finally {
    modalSaving.value = false;
  }
}

// 启停 / 删除
const { mutateAsync: toggleGroupStatus } = useToggleOrgGroupStatus();
const { mutateAsync: deleteGroup } = useDeleteOrgGroup();
const { mutateAsync: toggleDeptStatus } = useToggleOrgDeptStatus();
const { mutateAsync: deleteDept } = useDeleteOrgDept();

function handleToggleGroup(record: OrgGroup) {
  const next = record.status === 1 ? 0 : 1;
  const action = next === 1 ? '启用' : '停用';
  Modal.confirm({
    title: `确认${action}`,
    content: `${action}后，客户表单下拉${next === 1 ? '将重新显示' : '将不再显示'}该集团。`,
    okText: action,
    cancelText: '取消',
    onOk: () => toggleGroupStatus({ id: record.id!, status: next }),
  });
}

function handleToggleDept(record: OrgDept) {
  const next = record.status === 1 ? 0 : 1;
  const action = next === 1 ? '启用' : '停用';
  Modal.confirm({
    title: `确认${action}`,
    content: `${action}后，客户表单下拉${next === 1 ? '将重新显示' : '将不再显示'}该部门。`,
    okText: action,
    cancelText: '取消',
    onOk: () => toggleDeptStatus({ id: record.id!, status: next }),
  });
}

function handleDeleteGroup(record: OrgGroup) {
  Modal.confirm({
    title: '确认删除',
    content: `确定删除集团"${record.groupName}"吗？集团下有部门时将无法删除。`,
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: () => deleteGroup(record.id!),
  });
}

function handleDeleteDept(record: OrgDept) {
  Modal.confirm({
    title: '确认删除',
    content: `确定删除部门"${record.deptName}"吗？删除后不影响客户历史数据。`,
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: () => deleteDept(record.id!),
  });
}
</script>
