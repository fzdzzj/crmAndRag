import axios, {
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import { message } from 'ant-design-vue';
import { axiosInstance } from '@/api/apiClient';
import permissionCatalog from '@/constants/premission';

/**
 * 教程演示数据：教程进行期间把关键业务接口拦截到内存数据集，
 * 让新用户（尤其是空数据账号）能真实走完新建/推进/审批等操作；
 * 教程结束后立即恢复真实网络。未命中的接口一律透传线上。
 */

type MockRow = Record<string, unknown>;

interface MockContext {
  matches: RegExpMatchArray;
  params: Record<string, unknown>;
  body: Record<string, unknown>;
}

interface MockHandler {
  method: string;
  pattern: RegExp;
  handle: (ctx: MockContext) => unknown;
}

const STAGE_NAMES: Record<number, string> = {
  0: '种子',
  1: '潜在商机',
  2: '确认商机',
  3: '储备项目',
  4: '立项签约',
  5: '关闭',
};

const REMARK_TYPE_NAMES: Record<number, string> = {
  1: '喜好',
  2: '住址',
  3: '本人生日',
  4: '亲属生日',
  5: '自定义',
};

const RESERVED_KEYS = new Set(['pageNum', 'pageSize']);
const PAGE_DEFAULT_SIZE = 10;

function ok(data: unknown = null) {
  return { code: 1, msg: null, data };
}

function toBlob(content: string) {
  return new Blob([content], { type: 'text/csv;charset=utf-8' });
}

const CSV_TEMPLATE = 'column1,column2\n示例,演示数据\n';

function blobResponse(config: InternalAxiosRequestConfig, blob: Blob): AxiosResponse {
  return {
    data: blob,
    status: 200,
    statusText: 'OK',
    headers: { 'content-type': 'text/csv;charset=utf-8' },
    config,
  };
}

function plainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function textOf(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  if (value === null || value === undefined) {
    return '';
  }
  return JSON.stringify(value) ?? '';
}

function bodyIds(ctx: MockContext): unknown[] {
  const raw = ctx.body.ids;
  if (Array.isArray(raw)) {
    return raw;
  }
  if (ctx.body.id !== undefined) {
    return [ctx.body.id];
  }
  return [];
}

/** 不可变替换：vue-query 对查询结果做结构化共享，原地修改对象不会触发界面更新 */
function replaceRow(rows: MockRow[], id: unknown, patch: MockRow): MockRow[] {
  return rows.map((row) => (String(row.id) === String(id) ? { ...row, ...patch } : row));
}

/** 下拉搜索类接口：keyword 包含匹配 + 可选 status 过滤，返回全量数组 */
function filterByKeyword(rows: MockRow[], ctx: MockContext, field: string) {
  let result = rows;
  const keyword = textOf(ctx.params.keyword ?? ctx.body.keyword ?? '').trim();
  if (keyword) {
    result = result.filter((row) => textOf(row[field]).includes(keyword));
  }
  const status = ctx.params.status ?? ctx.body.status;
  if (status !== undefined && status !== '') {
    result = result.filter((row) => textOf(row.status ?? 1) === textOf(status));
  }
  return result;
}

function flattenFilters(source: Record<string, unknown>): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(source)) {
    if (plainObject(value)) {
      for (const [subKey, subValue] of Object.entries(value)) {
        result[`${key}.${subKey}`] = subValue;
      }
    } else {
      result[key] = value;
    }
  }
  return result;
}

function normalizeKey(rawKey: string) {
  // 兼容 dto.approvalStatus / filters.industry 两种前缀
  return rawKey.includes('.') ? rawKey.split('.').slice(1).join('.') : rawKey;
}

function matchFilters(rows: MockRow[], ctx: MockContext) {
  const filters = flattenFilters({ ...ctx.params, ...ctx.body });
  return rows.filter((row) => {
    for (const [rawKey, rawValue] of Object.entries(filters)) {
      if (RESERVED_KEYS.has(rawKey) || rawValue === undefined || rawValue === null || rawValue === '') {
        continue;
      }
      const key = normalizeKey(rawKey);
      if (key === 'isDeleted') {
        if (textOf(row.isDeleted ?? false) !== textOf(rawValue)) {
          return false;
        }
        continue;
      }
      const cell = row[key];
      if (cell === undefined || cell === null) {
        return false;
      }
      if (Array.isArray(rawValue)) {
        if (!rawValue.some((item) => textOf(item) === textOf(cell))) {
          return false;
        }
      } else if (typeof rawValue === 'string' && typeof cell === 'string') {
        if (!cell.includes(rawValue)) {
          return false;
        }
      } else if (textOf(cell) !== textOf(rawValue)) {
        return false;
      }
    }
    return true;
  });
}

function pageOf(rows: MockRow[], ctx: MockContext) {
  const source = flattenFilters({ ...ctx.params, ...ctx.body });
  const pageNum = Math.max(Number(source.pageNum ?? 1) || 1, 1);
  const pageSize = Math.max(Number(source.pageSize ?? PAGE_DEFAULT_SIZE) || PAGE_DEFAULT_SIZE, 1);
  const start = (pageNum - 1) * pageSize;
  return {
    records: rows.slice(start, start + pageSize),
    total: rows.length,
    size: pageSize,
    current: pageNum,
    pages: Math.ceil(rows.length / pageSize),
  };
}

// ---------------- 内存数据集 ----------------

let nextId = 90000;
function allocId() {
  nextId += 1;
  return nextId;
}

interface MockStore {
  groups: MockRow[];
  depts: MockRow[];
  sysDepts: MockRow[];
  companies: MockRow[];
  contacts: MockRow[];
  sales: MockRow[];
  approvals: MockRow[];
  stageChangeRecords: MockRow[];
  tasks: MockRow[];
  activities: MockRow[];
  contracts: MockRow[];
  contractOrders: MockRow[];
  invoices: MockRow[];
  payments: MockRow[];
  roles: MockRow[];
  users: MockRow[];
  assists: MockRow[];
  applications: MockRow[];
  handovers: MockRow[];
  projectFiles: MockRow[];
}

function buildStore(): MockStore {
  // 全部为虚构演示数据：公司/人员/联系方式均非真实主体，避免与任何真实库混淆
  return {
    groups: [
      { id: 1, groupName: '北极星集团', status: 1, createTime: '2026-08-01 09:00:00' },
      { id: 2, groupName: '浪潮控股', status: 1, createTime: '2026-08-02 09:00:00' },
      { id: 3, groupName: '曙光实业', status: 1, createTime: '2026-08-03 09:00:00' },
    ],
    depts: [
      { id: 11, groupId: 1, groupName: '北极星集团', deptName: '装备制造事业部', status: 1, createTime: '2026-08-01 09:10:00' },
      { id: 12, groupId: 1, groupName: '北极星集团', deptName: '信息技术部', status: 1, createTime: '2026-08-01 09:11:00' },
      { id: 13, groupId: 2, groupName: '浪潮控股', deptName: '渠道运营部', status: 1, createTime: '2026-08-02 09:10:00' },
      { id: 14, groupId: 3, groupName: '曙光实业', deptName: '品牌推广部', status: 1, createTime: '2026-08-03 09:10:00' },
    ],
    companies: [
      {
        id: 101, companyName: '北极星智能装备有限公司', industry: '制造业', customerType: '直销',
        belongGroup: '北极星集团', dept: '装备制造事业部', address: '苏州市工业园区星湖街', description: 'MES 系统选型中',
        grade: 8, ownerName: '陈昊', creatorName: '陈静', createTime: '2026-08-01 10:00:00',
        updateTime: '2026-08-20 14:00:00', isDeleted: false,
      },
      {
        id: 102, companyName: '浪潮云服商贸有限公司', industry: '批发零售', customerType: '代理',
        belongGroup: '浪潮控股', dept: '渠道运营部', address: '杭州市西湖区文一西路', description: '渠道管理系统升级',
        grade: 5, ownerName: '苏小雨', creatorName: '陈静', createTime: '2026-08-03 10:00:00',
        updateTime: '2026-08-22 11:00:00', isDeleted: false,
      },
      {
        id: 103, companyName: '曙光文化传媒有限公司', industry: '文化传媒', customerType: '直销',
        belongGroup: '曙光实业', dept: '品牌推广部', address: '成都市高新区天府大道', description: '小程序二期开发意向',
        grade: 3, ownerName: '陈昊', creatorName: '陈昊', createTime: '2026-08-05 10:00:00',
        updateTime: '2026-08-18 16:00:00', isDeleted: false,
      },
    ],
    contacts: [
      {
        id: 201, name: '高翔', position: '采购总监', dept: '采购部', companyName: '北极星智能装备有限公司',
        companyId: 101, relationLevel: 7, mobile: '13811110001', gender: 1, creatorName: '陈昊',
        remarks: [
          { id: 1, remarkType: 1, remarkTypeDesc: '喜好', remarkContent: '喝普洱茶，偏好正式场合沟通' },
          { id: 2, remarkType: 3, remarkTypeDesc: '本人生日', remarkDate: '1979-06-18' },
        ],
        isDeleted: false, createTime: '2026-08-01 11:00:00',
      },
      {
        id: 202, name: '吴婷', position: '信息部经理', dept: '信息技术部', companyName: '北极星智能装备有限公司',
        companyId: 101, relationLevel: 5, mobile: '13811110002', gender: 2, creatorName: '陈昊',
        remarks: [
          { id: 3, remarkType: 5, remarkTypeDesc: '自定义', remarkContent: '主导 MES 选型的技术评估' },
          { id: 4, remarkType: 2, remarkTypeDesc: '住址', remarkContent: '苏州市工业园区星湖街' },
        ],
        isDeleted: false, createTime: '2026-08-01 11:10:00',
      },
      {
        id: 203, name: '罗静', position: '供应链主管', dept: '运营部', companyName: '浪潮云服商贸有限公司',
        companyId: 102, relationLevel: 6, mobile: '13811110003', gender: 2, creatorName: '苏小雨',
        remarks: [
          { id: 5, remarkType: 1, remarkTypeDesc: '喜好', remarkContent: '瑜伽、烘焙' },
          { id: 6, remarkType: 4, remarkTypeDesc: '亲属生日', remarkName: '丈夫', remarkDate: '1983-05-20' },
        ],
        isDeleted: false, createTime: '2026-08-03 11:00:00',
      },
      {
        id: 204, name: '韩磊', position: '市场总监', dept: '品牌推广部', companyName: '曙光文化传媒有限公司',
        companyId: 103, relationLevel: 4, mobile: '13811110004', gender: 1, creatorName: '陈昊',
        remarks: [
          { id: 7, remarkType: 5, remarkTypeDesc: '自定义', remarkContent: '关注短视频投放转化效果' },
        ],
        isDeleted: false, createTime: '2026-08-05 11:00:00',
      },
    ],
    sales: [
      { id: 301, opportunityName: '北极星 MES 系统采购项目', companyName: '北极星智能装备有限公司', contactName: '高翔', amount: 860000, stage: 2, expectedCloseDate: '2026-09-30', source: '展会', ownerName: '陈昊', creatorName: '陈静', approverName: '陈静', description: '替换旧产线管理系统', createTime: '2026-08-06 10:00:00', updateTime: '2026-08-20 10:00:00' },
      { id: 302, opportunityName: '浪潮云服渠道管理系统', companyName: '浪潮云服商贸有限公司', contactName: '罗静', amount: 450000, stage: 3, expectedCloseDate: '2026-10-15', source: '客户转介绍', ownerName: '苏小雨', creatorName: '苏小雨', approverName: '陈静', description: '仓储与渠道一体化', createTime: '2026-08-08 10:00:00', updateTime: '2026-08-25 10:00:00' },
      { id: 303, opportunityName: '曙光传媒小程序开发', companyName: '曙光文化传媒有限公司', contactName: '韩磊', amount: 220000, stage: 4, expectedCloseDate: '2026-09-10', source: '官网留资', ownerName: '陈昊', creatorName: '陈昊', approverName: '陈静', description: '会员小程序二期', createTime: '2026-08-10 10:00:00', updateTime: '2026-08-24 10:00:00' },
    ],
    approvals: [
      {
        id: 401, opportunityId: 302, opportunityName: '浪潮云服渠道管理系统', currentStage: '储备项目',
        targetStage: '立项签约', message: '方案与报价已获客户确认，申请立项签约', applyTime: '2026-08-25 10:00:00',
        approverName: '陈静', approvalStatus: 0, approvalOpinion: '',
        assistUsers: [{ assistUserName: '苏小雨', assistUserDeptName: '销售部', assistStatus: 0 }],
      },
    ],
    stageChangeRecords: [
      { id: 501, opportunityId: 302, currentStage: '确认商机', targetStage: '储备项目', approvalStatus: 1, approvalStatusDesc: '已同意', approvalOpinion: '方案细节已评审通过', applyTime: '2026-08-18 15:00:00', assistUsers: [{ assistUserName: '苏小雨', assistUserDeptName: '销售部', assistStatus: 1 }] },
      { id: 502, opportunityId: 303, currentStage: '储备项目', targetStage: '立项签约', approvalStatus: 1, approvalStatusDesc: '已同意', approvalOpinion: '同意', applyTime: '2026-08-24 09:00:00', assistUsers: [{ assistUserName: '陈昊', assistUserDeptName: '销售部', assistStatus: 1 }] },
    ],
    tasks: [
      { id: 701, taskTitle: '给高翔发送 MES 报价单', taskContent: '发送报价并约下次技术交流会', taskType: '电话回访', status: 0, priority: 2, contactName: '高翔', companyName: '北极星智能装备有限公司', opportunityTitle: '北极星 MES 系统采购项目', deadline: '2026-08-30 18:00:00', assigneeName: '陈昊', creatorName: '陈昊', createTime: '2026-08-26 10:00:00' },
      { id: 702, taskTitle: '跟进罗静系统升级需求', taskContent: '确认立项进度与预算', taskType: '上门拜访', status: 1, priority: 1, contactName: '罗静', companyName: '浪潮云服商贸有限公司', opportunityTitle: '浪潮云服渠道管理系统', deadline: '2026-08-29 18:00:00', assigneeName: '苏小雨', creatorName: '苏小雨', createTime: '2026-08-26 10:10:00' },
      { id: 703, taskTitle: '整理曙光传媒二期需求清单', taskContent: '汇总小程序二期功能范围', taskType: '资料整理', status: 2, priority: 0, contactName: '韩磊', companyName: '曙光文化传媒有限公司', opportunityTitle: '曙光传媒小程序开发', deadline: '2026-08-21 18:00:00', assigneeName: '陈昊', creatorName: '陈昊', createTime: '2026-08-19 10:00:00' },
    ],
    activities: [
      { id: 601, activityTitle: '北极星首次需求调研', activityContent: '了解产线管理现状、预算与决策链', activityTime: '2026-08-05 14:00:00', activityType: '客户拜访', activityDuration: 60, remark: '', opportunityName: '北极星 MES 系统采购项目', companyName: '北极星智能装备有限公司', creatorName: '陈昊', createTime: '2026-08-05 16:00:00' },
      { id: 602, activityTitle: '浪潮云服方案演示', activityContent: '演示仓储配送一体化方案', activityTime: '2026-08-22 10:00:00', activityType: '产品演示', activityDuration: 90, remark: '', opportunityName: '浪潮云服渠道管理系统', companyName: '浪潮云服商贸有限公司', creatorName: '苏小雨', createTime: '2026-08-22 12:00:00' },
      { id: 603, activityTitle: '曙光传媒需求交流', activityContent: '小程序二期功能范围沟通', activityTime: '2026-08-24 15:00:00', activityType: '技术交流', activityDuration: 120, remark: '', opportunityName: '曙光传媒小程序开发', companyName: '曙光文化传媒有限公司', creatorName: '陈昊', createTime: '2026-08-24 17:00:00' },
    ],
    contracts: [
      {
        id: 801, contractName: '浪潮云服渠道管理系统合同', contractNo: 'HT-DEMO-001', companyName: '浪潮云服商贸有限公司',
        opportunityName: '浪潮云服渠道管理系统', totalAmount: 450000, signDate: '2026-08-20', startDate: '2026-08-21',
        endDate: '2026-12-31', contractStatus: 1, ownerName: '苏小雨', creatorName: '苏小雨', createTime: '2026-08-20 14:00:00',
      },
    ],
    contractOrders: [
      { id: 811, contractId: 801, productName: '渠道中台系统', quantity: 1, unitPrice: 450000, amount: 450000, remark: '含一年维保' },
    ],
    invoices: [
      { id: 901, invoiceNo: 'INV-DEMO-001', invoiceAmount: 100000, invoiceDate: '2026-08-22', invoiceType: '增值税专用发票', remark: '', contractName: '浪潮云服渠道管理系统合同' },
      { id: 902, invoiceNo: 'INV-DEMO-002', invoiceAmount: 150000, invoiceDate: '2026-08-25', invoiceType: '增值税普通发票', remark: '', contractName: '浪潮云服渠道管理系统合同' },
    ],
    payments: [
      { id: 911, paymentNo: 'SK-DEMO-001', paymentAmount: 135000, paymentDate: '2026-08-23', paymentMethod: '银行转账', paymentStatus: '已确认', contractName: '浪潮云服渠道管理系统合同' },
      { id: 912, paymentNo: 'SK-DEMO-002', paymentAmount: 90000, paymentDate: '2026-08-26', paymentMethod: '银行承兑', paymentStatus: '待确认', contractName: '浪潮云服渠道管理系统合同' },
    ],
    roles: [
      { id: 1, roleName: '系统管理员', createTime: '2026-07-01 09:00:00' },
      { id: 2, roleName: '销售总监', createTime: '2026-07-01 09:01:00' },
      { id: 3, roleName: '销售', createTime: '2026-07-01 09:02:00' },
      { id: 4, roleName: '财务', createTime: '2026-07-01 09:03:00' },
    ],
    users: [
      { id: 1, realName: '陈静', username: 'demo-admin', email: 'demo-admin@democrm.cn', phone: '13822220001', deptName: '综合管理部', roleName: '系统管理员', status: 1, createTime: '2026-07-01 10:00:00' },
      { id: 2, realName: '陈昊', username: 'demo-director', email: 'demo-director@democrm.cn', phone: '13822220002', deptName: '销售部', roleName: '销售总监', status: 1, createTime: '2026-07-02 10:00:00' },
      { id: 3, realName: '苏小雨', username: 'demo-sales', email: 'demo-sales@democrm.cn', phone: '13822220003', deptName: '销售部', roleName: '销售', status: 1, createTime: '2026-07-03 10:00:00' },
      { id: 4, realName: '郑凯', username: 'demo-finance', email: 'demo-finance@democrm.cn', phone: '13822220004', deptName: '财务部', roleName: '财务', status: 1, createTime: '2026-07-04 10:00:00' },
    ],
    assists: [
      { id: 1001, modelName: 'sales_stage_approval', recordId: 401, recordTitle: '浪潮云服渠道管理系统 — 推进到立项签约', recordContent: '方案与报价已获客户确认', applyPurpose: '需要技术方案支持', applyRequirement: '8 月底前输出部署方案', recordTime: '2026-08-25 10:00:00', applicantName: '苏小雨', createTime: '2026-08-25 10:00:00', assistStatus: 0, assistContent: '', rejectReason: '', assistTime: '', companyId: 102, companyName: '浪潮云服商贸有限公司', contactId: 203, contactName: '罗静', opportunityId: 302, opportunityName: '浪潮云服渠道管理系统' },
      { id: 1002, modelName: 'business_activity', recordId: 603, recordTitle: '曙光传媒需求交流', recordContent: '小程序二期功能沟通', applyPurpose: '需要产品经理支持', applyRequirement: '现场演示小程序模块', recordTime: '2026-08-24 15:00:00', applicantName: '陈昊', createTime: '2026-08-24 15:30:00', assistStatus: 1, assistContent: '已完成现场演示并整理纪要', rejectReason: '', assistTime: '2026-08-26 16:00:00', companyId: 103, companyName: '曙光文化传媒有限公司', contactId: 204, contactName: '韩磊', opportunityId: 303, opportunityName: '曙光传媒小程序开发' },
    ],
    applications: [
      { id: 1101, modelName: 'business_activity', recordId: 603, recordTitle: '曙光传媒需求交流', recordContent: '小程序二期功能沟通', applyPurpose: '需要产品经理支持', applyRequirement: '现场演示小程序模块', recordTime: '2026-08-24 15:00:00', applicantName: '我', assistUserName: '陈昊', assistUserDeptName: '销售部', assistStatus: 2, rejectReason: '当天有其他客户安排', assistContent: '', assistTime: '', createTime: '2026-08-24 15:30:00', companyId: 103, companyName: '曙光文化传媒有限公司', contactId: 204, contactName: '韩磊', opportunityId: 303, opportunityName: '曙光传媒小程序开发' },
      { id: 1103, modelName: 'business_activity', recordId: 603, parentId: 1101, recordTitle: '曙光传媒需求交流（追加）', recordContent: '小程序二期功能沟通', applyPurpose: '需要售前补充竞品对比材料', applyRequirement: '两天内提供', recordTime: '2026-08-24 15:00:00', applicantName: '我', assistUserName: '苏小雨', assistUserDeptName: '销售部', assistStatus: 1, rejectReason: '', assistContent: '对比材料已发邮件', assistTime: '2026-08-25 11:00:00', createTime: '2026-08-25 09:00:00', companyId: 103, companyName: '曙光文化传媒有限公司', contactId: 204, contactName: '韩磊', opportunityId: 303, opportunityName: '曙光传媒小程序开发' },
      { id: 1102, modelName: 'sales_stage_approval', recordId: 502, recordTitle: '曙光传媒小程序开发 — 推进到立项签约', recordContent: '二期需求清单已确认', applyPurpose: '需要售前协助整理材料', applyRequirement: '两天内提供竞品对比', recordTime: '2026-08-24 09:00:00', applicantName: '我', assistUserName: '苏小雨', assistUserDeptName: '销售部', assistStatus: 1, rejectReason: '', assistContent: '对比材料已发邮件', assistTime: '2026-08-25 11:00:00', createTime: '2026-08-24 09:10:00', companyId: 103, companyName: '曙光文化传媒有限公司', contactId: 204, contactName: '韩磊', opportunityId: 303, opportunityName: '曙光传媒小程序开发' },
    ],
    sysDepts: [
      { id: 1, deptName: '综合管理部', parentId: 0, sort: 1, status: 1, createTime: '2026-07-01 09:00:00' },
      { id: 2, deptName: '销售部', parentId: 0, sort: 2, status: 1, createTime: '2026-07-01 09:01:00' },
      { id: 3, deptName: '财务部', parentId: 0, sort: 3, status: 1, createTime: '2026-07-01 09:02:00' },
      { id: 4, deptName: '技术部', parentId: 0, sort: 4, status: 1, createTime: '2026-07-01 09:03:00' },
    ],
    projectFiles: [
      { id: 1, opportunityId: 301, fileName: 'MES 需求调研纪要.pdf', category: '拜访记录', theme: '首次需求调研', uploaderName: '陈昊', uploadTime: '2026-08-05 17:00:00' },
      { id: 2, opportunityId: 301, fileName: 'MES 技术方案 v1.docx', category: '方案', theme: '技术方案初稿', uploaderName: '陈昊', uploadTime: '2026-08-12 10:30:00' },
      { id: 3, contractId: 801, fileName: '渠道中台合同扫描件.pdf', category: '项目合同', theme: '签约合同存档', uploaderName: '苏小雨', uploadTime: '2026-08-20 15:00:00' },
    ],
    handovers: [
      { id: 1, fromUserName: '苏小雨', toUserName: '陈昊', companyName: '北极星智能装备有限公司', createTime: '2026-08-15 10:00:00' },
    ],
  };
}

let store: MockStore = buildStore();

function findRow(rows: MockRow[], id: unknown) {
  return rows.find((row) => String(row.id) === String(id));
}

function stageName(stage: unknown) {
  const index = Number(stage);
  return STAGE_NAMES[Number.isFinite(index) ? index : 0] ?? '种子';
}

function resolveUserName(id: unknown) {
  const user = id === undefined ? undefined : findRow(store.users, id);
  return (user?.realName as string) ?? '我';
}

function nowText() {
  return '2026-08-29 10:00:00';
}

// ---------------- 权限目录 ----------------

interface PermissionItem {
  id: number;
  permissionsName: string;
  permissionsDesc: string;
  parentPermissionId?: number;
}

function flattenPermissions(): PermissionItem[] {
  const items: PermissionItem[] = [];
  for (const group of Object.values(permissionCatalog)) {
    for (const main of group.mainPermissions) {
      items.push({
        id: main.id,
        permissionsName: main.permissionsName,
        permissionsDesc: main.permissionsDesc,
      });
    }
    for (const sub of group.subPermissions) {
      items.push({
        id: sub.id,
        permissionsName: sub.permissionsName,
        permissionsDesc: sub.permissionsDesc,
        parentPermissionId: sub.parentPermissionId,
      });
    }
  }
  return items;
}

// ---------------- 处理器 ----------------

const handlers: MockHandler[] = [
  // 客户域
  { method: 'get', pattern: /^\/company\/custom$/, handle: (ctx) => ok(pageOf(matchFilters(store.companies, ctx), ctx)) },
  { method: 'get', pattern: /^\/contact\/getContactByCompanyId$/, handle: (ctx) => ok(store.contacts.filter((c) => String(c.companyId) === String(ctx.params.id))) },
  { method: 'get', pattern: /^\/contact\/search$/, handle: (ctx) => ok(pageOf(matchFilters(store.contacts, ctx), ctx)) },
  { method: 'post', pattern: /^\/company$/, handle: (ctx) => {
    const row: MockRow = {
      id: allocId(), companyName: (ctx.body.companyName as string) ?? '未命名客户', industry: ctx.body.industry ?? '',
      customerType: ctx.body.customerType ?? '', belongGroup: ctx.body.belongGroup ?? '', dept: ctx.body.dept ?? '',
      address: ctx.body.address ?? '', description: ctx.body.description ?? '', grade: Number(ctx.body.grade ?? 0),
      ownerName: '我', creatorName: '我', createTime: nowText(), updateTime: nowText(), isDeleted: false,
    };
    store.companies.unshift(row);
    return ok(true);
  } },
  { method: 'put', pattern: /^\/company$/, handle: (ctx) => {
    store.companies = replaceRow(store.companies, ctx.body.id, { ...ctx.body, updateTime: nowText() });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/company\/recover$/, handle: (ctx) => {
    for (const id of (ctx.body.ids ?? []) as unknown[]) {
      const row = findRow(store.companies, id);
      if (row) store.companies = replaceRow(store.companies, id, { isDeleted: false });
    }
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/company$/, handle: (ctx) => {
    for (const id of bodyIds(ctx)) {
      if (findRow(store.companies, id)) {
        store.companies = replaceRow(store.companies, id, { isDeleted: true });
      }
    }
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/company\/logical/, handle: (ctx) => {
    const ids = bodyIds(ctx).length > 0 ? bodyIds(ctx) : [ctx.matches[1]];
    store.companies = store.companies.filter((row) => !ids.some((id) => String(id) === String(row.id)));
    return ok(true);
  } },
  { method: 'get', pattern: /^\/company\/template$/, handle: () => toBlob(CSV_TEMPLATE) },
  { method: 'get', pattern: /^\/company\/excel$/, handle: () => toBlob(CSV_TEMPLATE) },

  // 联系人域
  { method: 'post', pattern: /^\/contact$/, handle: (ctx) => {
    const company = ctx.body.companyId === undefined ? undefined : findRow(store.companies, ctx.body.companyId);
    store.contacts.unshift({
      id: allocId(), name: (ctx.body.name as string) ?? '未命名联系人', position: ctx.body.position ?? '',
      dept: ctx.body.dept ?? '', companyName: (company?.companyName as string) ?? '', companyId: company?.id ?? undefined,
      relationLevel: Number(ctx.body.relationLevel ?? 0), mobile: ctx.body.mobile ?? '', gender: ctx.body.gender,
      creatorName: '我', remarks: [], isDeleted: false, createTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/contact$/, handle: (ctx) => {
    // useUpdateContact 提交的是数组：[{...contact}]
    const items = Array.isArray(ctx.body.ids) ? ctx.body.ids : [ctx.body];
    for (const item of items) {
      if (plainObject(item)) {
        const row = findRow(store.contacts, item.id);
        if (row) {
          // 提交载荷会剥掉 remarkTypeDesc，按 remarkType 推导补回，保证列表备注列正常显示
          const remarks = Array.isArray(item.remarks)
            ? (item.remarks as MockRow[]).map((remark) => ({
                ...remark,
                remarkTypeDesc: REMARK_TYPE_NAMES[Number(remark.remarkType)] ?? '',
              }))
            : item.remarks;
          store.contacts = replaceRow(store.contacts, item.id, { ...item, remarks });
        }
      }
    }
    return ok(true);
  } },
  { method: 'put', pattern: /^\/contact\/recover$/, handle: (ctx) => {
    for (const id of (ctx.body.ids ?? []) as unknown[]) {
      const row = findRow(store.contacts, id);
      if (row) store.contacts = replaceRow(store.contacts, id, { isDeleted: false });
    }
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/contact$/, handle: (ctx) => {
    for (const id of bodyIds(ctx)) {
      if (findRow(store.contacts, id)) {
        store.contacts = replaceRow(store.contacts, id, { isDeleted: true });
      }
    }
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/contact\/logical/, handle: (ctx) => {
    const ids = bodyIds(ctx).length > 0 ? bodyIds(ctx) : [ctx.matches[1]];
    store.contacts = store.contacts.filter((row) => !ids.some((id) => String(id) === String(row.id)));
    return ok(true);
  } },
  { method: 'get', pattern: /^\/contact\/template$/, handle: () => toBlob(CSV_TEMPLATE) },
  { method: 'get', pattern: /^\/contact\/excel$/, handle: () => toBlob(CSV_TEMPLATE) },

  // 销售域
  { method: 'get', pattern: /^\/sales\/query$/, handle: (ctx) => ok(pageOf(matchFilters(store.sales, ctx), ctx)) },
  { method: 'get', pattern: /^\/sales$/, handle: (ctx) => ok(pageOf(matchFilters(store.sales, ctx), ctx)) },
  { method: 'post', pattern: /^\/sales$/, handle: (ctx) => {
    const company = ctx.body.companyId === undefined ? undefined : findRow(store.companies, ctx.body.companyId);
    store.sales.unshift({
      id: allocId(), opportunityName: (ctx.body.opportunityName as string) ?? '未命名商机',
      companyName: (company?.companyName as string) ?? (ctx.body.companyName as string) ?? '',
      contactName: ctx.body.contactName ?? '', amount: Number(ctx.body.amount ?? 0), stage: Number(ctx.body.stage ?? 0),
      expectedCloseDate: ctx.body.expectedCloseDate ?? '', source: ctx.body.source ?? '',
      ownerName: '我', creatorName: '我', approverName: resolveUserName(ctx.body.approverId),
      description: ctx.body.description ?? '', createTime: nowText(), updateTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/sales$/, handle: (ctx) => {
    store.sales = replaceRow(store.sales, ctx.body.id, { ...ctx.body, updateTime: nowText() });
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/sales\/(\d+)$/, handle: (ctx) => {
    store.sales = store.sales.filter((row) => String(row.id) !== ctx.matches[1]);
    return ok(true);
  } },
  { method: 'get', pattern: /^\/sales\/detail\/(\d+)$/, handle: (ctx) => {
    const sale = findRow(store.sales, ctx.matches[1]);
    if (!sale) return ok(null);
    const stage = stageName(sale.stage);
    const activities = store.activities.filter((a) => a.opportunityName === sale.opportunityName);
    return ok({
      ...sale,
      activitiesByStage: activities.length > 0 ? { [stage]: activities } : {},
      stageChangeRecords: store.stageChangeRecords.filter((r) => String(r.opportunityId) === String(sale.id)),
    });
  } },
  { method: 'get', pattern: /^\/sales\/stage$/, handle: (ctx) => ok(pageOf(matchFilters(store.approvals, ctx), ctx)) },
  { method: 'get', pattern: /^\/sales\/stage\/attachment$/, handle: (ctx) => {
    const ids = textOf(ctx.params.approvalIds).split(',').filter(Boolean);
    const rows = ids.includes('401') || ids.includes('') ? [{ id: 9001, andId: 401, fileName: '立项方案演示.pdf' }] : [];
    return ok(ids.length === 0 ? [] : rows.filter((row) => ids.includes(String(row.andId))));
  } },
  { method: 'post', pattern: /^\/sales\/stage\/approval$/, handle: (ctx) => {
    // FileUploadModal 提交扁平点号键（salesStageApproval.message 等），兼容嵌套对象两种形态
    const nested = plainObject(ctx.body.salesStageApproval) ? ctx.body.salesStageApproval : {};
    const read = (dotted: string, key: string): unknown => ctx.body[dotted] ?? nested[key];
    const opportunityId = read('salesStageApproval.opportunityId', 'opportunityId');
    const sale = opportunityId === undefined || opportunityId === null ? undefined : findRow(store.sales, opportunityId);
    const assistIds: unknown[] = [];
    for (const [key, value] of Object.entries(ctx.body)) {
      const m = /^salesStageApproval\.assistApplyList\[(\d+)\]\.assistUserId$/.exec(key);
      if (m && value !== undefined && value !== null && value !== '') {
        assistIds[Number(m[1])] = value;
      }
    }
    const targetStage = read('salesStageApproval.targetStage', 'targetStage');
    store.approvals.unshift({
      id: allocId(), opportunityId: sale?.id ?? opportunityId,
      opportunityName: (sale?.opportunityName as string) ?? '',
      currentStage: stageName(sale?.stage ?? 0),
      targetStage: targetStage === undefined || targetStage === '' ? stageName(Number(sale?.stage ?? 0) + 1) : stageName(targetStage),
      message: (read('salesStageApproval.message', 'message') as string) ?? '',
      applyTime: nowText(),
      approverName: resolveUserName(read('salesStageApproval.approverId', 'approverId')),
      approvalStatus: 0, approvalOpinion: '',
      assistUsers: assistIds.filter(Boolean).map((id) => ({ assistUserName: resolveUserName(id), assistUserDeptName: '销售部', assistStatus: 0 })),
    });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/sales\/stage$/, handle: (ctx) => {
    const approval = findRow(store.approvals, ctx.body.id);
    if (approval) {
      const status = Number(ctx.body.approvalStatus ?? 0);
      store.approvals = replaceRow(store.approvals, ctx.body.id, {
        approvalStatus: status,
        approvalOpinion: ctx.body.approvalOpinion ?? '',
      });
      if (status === 1) {
        const sale = approval.opportunityId === undefined ? undefined : findRow(store.sales, approval.opportunityId);
        if (sale) {
          store.sales = replaceRow(store.sales, sale.id, { stage: Math.min(Number(sale.stage ?? 0) + 1, 5) });
        }
      }
      store.stageChangeRecords = [
        {
          id: allocId(), opportunityId: approval.opportunityId, currentStage: approval.currentStage,
          targetStage: approval.targetStage, approvalStatus: status,
          approvalStatusDesc: status === 1 ? '已同意' : status === 2 ? '已拒绝' : '退回修改',
          approvalOpinion: ctx.body.approvalOpinion ?? '', applyTime: approval.applyTime,
          assistUsers: approval.assistUsers,
        },
        ...store.stageChangeRecords,
      ];
    }
    return ok(true);
  } },

  // 合同与财务
  { method: 'get', pattern: /^\/contract$/, handle: (ctx) => ok(pageOf(store.contracts, ctx)) },
  { method: 'get', pattern: /^\/contract\/(\d+)$/, handle: (ctx) => {
    const contract = findRow(store.contracts, ctx.matches[1]);
    return ok({
      contract,
      orders: store.contractOrders.filter((o) => String(o.contractId) === ctx.matches[1]),
    });
  } },
  { method: 'post', pattern: /^\/contract$/, handle: (ctx) => {
    const contract = plainObject(ctx.body.contract) ? ctx.body.contract : {};
    store.contracts.unshift({
      id: allocId(), ...contract, createTime: nowText(),
    });
    for (const order of (Array.isArray(ctx.body.orders) ? ctx.body.orders : []) as MockRow[]) {
      store.contractOrders.push({ id: allocId(), contractId: store.contracts[0].id, ...order });
    }
    return ok(true);
  } },
  { method: 'put', pattern: /^\/contract$/, handle: (ctx) => {
    const contract = plainObject(ctx.body.contract) ? ctx.body.contract : ctx.body;
    if (contract.id !== undefined) {
      store.contracts = replaceRow(store.contracts, contract.id, contract);
    }
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/contract$/, handle: (ctx) => {
    const ids = bodyIds(ctx);
    store.contracts = store.contracts.filter((row) => !ids.some((id) => String(id) === String(row.id)));
    return ok(true);
  } },
  { method: 'post', pattern: /^\/invoice\/info\/query$/, handle: (ctx) => ok(pageOf(store.invoices, ctx)) },
  { method: 'post', pattern: /^\/invoice\/info$/, handle: (ctx) => {
    store.invoices.unshift({ id: allocId(), ...ctx.body });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/invoice\/info$/, handle: (ctx) => {
    store.invoices = replaceRow(store.invoices, ctx.body.id, ctx.body);
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/invoice\/info$/, handle: () => ok(true) },
  { method: 'post', pattern: /^\/payment\/record\/query$/, handle: (ctx) => ok(pageOf(store.payments, ctx)) },
  { method: 'post', pattern: /^\/payment\/record$/, handle: (ctx) => {
    store.payments.unshift({ id: allocId(), ...ctx.body });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/payment\/record$/, handle: (ctx) => {
    store.payments = replaceRow(store.payments, ctx.body.id, ctx.body);
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/payment\/record$/, handle: () => ok(true) },
  { method: 'get', pattern: /^\/invoice\/info\/(\d+)$/, handle: (ctx) => ok(findRow(store.invoices, ctx.matches[1]) ?? null) },
  { method: 'get', pattern: /^\/payment\/record\/(\d+)$/, handle: (ctx) => ok(findRow(store.payments, ctx.matches[1]) ?? null) },

  // 联络域
  { method: 'post', pattern: /^\/contactTask\/query$/, handle: (ctx) => ok(pageOf(matchFilters(store.tasks, ctx), ctx)) },
  { method: 'get', pattern: /^\/contactTask\/all$/, handle: () => ok(store.tasks) },
  { method: 'post', pattern: /^\/contactTask\/create$/, handle: (ctx) => {
    const contact = ctx.body.contactId === undefined ? undefined : findRow(store.contacts, ctx.body.contactId);
    const sale = ctx.body.opportunityId === undefined ? undefined : findRow(store.sales, ctx.body.opportunityId);
    store.tasks.unshift({
      id: allocId(), taskTitle: (ctx.body.taskTitle as string) ?? '未命名任务',
      taskContent: ctx.body.taskContent ?? '', taskType: ctx.body.taskType ?? '',
      status: Number(ctx.body.status ?? 0), priority: Number(ctx.body.priority ?? 1),
      contactName: (contact?.name as string) ?? '', companyName: (contact?.companyName as string) ?? '',
      opportunityTitle: (sale?.opportunityName as string) ?? '', deadline: (ctx.body.endTime as string) ?? '',
      assigneeName: resolveUserName(ctx.body.assigneeId), creatorName: '我', createTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/contactTask\/update$/, handle: (ctx) => {
    const row = findRow(store.tasks, ctx.body.id);
    if (row) {
      store.tasks = replaceRow(store.tasks, ctx.body.id, {
        ...ctx.body,
        assigneeName: resolveUserName(ctx.body.assigneeId ?? row.assigneeId),
      });
    }
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/contactTask\/(\d+)$/, handle: (ctx) => {
    store.tasks = store.tasks.filter((row) => String(row.id) !== ctx.matches[1]);
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/contactTask\/batch$/, handle: (ctx) => {
    const ids = bodyIds(ctx);
    store.tasks = store.tasks.filter((row) => !ids.some((id) => String(id) === String(row.id)));
    return ok(true);
  } },
  { method: 'get', pattern: /^\/contactTask\/(\d+)$/, handle: (ctx) => ok(findRow(store.tasks, ctx.matches[1]) ?? null) },
  { method: 'post', pattern: /^\/business\/activity\/query$/, handle: (ctx) => ok(pageOf(matchFilters(store.activities, ctx), ctx)) },
  { method: 'post', pattern: /^\/business\/activity$/, handle: (ctx) => {
    const sale = ctx.body.opportunityId === undefined ? undefined : findRow(store.sales, ctx.body.opportunityId);
    store.activities.unshift({
      id: allocId(), ...ctx.body, opportunityName: (sale?.opportunityName as string) ?? '',
      companyName: (sale?.companyName as string) ?? '', creatorName: '我', createTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/business\/activity$/, handle: (ctx) => {
    store.activities = replaceRow(store.activities, ctx.body.id, ctx.body);
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/business\/activity$/, handle: (ctx) => {
    const ids = bodyIds(ctx);
    store.activities = store.activities.filter((row) => !ids.some((id) => String(id) === String(row.id)));
    return ok(true);
  } },
  { method: 'get', pattern: /^\/business\/activity\/(\d+)$/, handle: (ctx) => ok(findRow(store.activities, ctx.matches[1]) ?? null) },
  { method: 'get', pattern: /^\/business\/activity\/(\d+)\/attachments$/, handle: () => ok([]) },
  { method: 'get', pattern: /^\/taskComment\/task\/(\d+)/, handle: () => ok([]) },

  // 协助域
  { method: 'get', pattern: /^\/assist\/my$/, handle: (ctx) => ok(pageOf(matchFilters(store.assists, ctx), ctx)) },
  { method: 'get', pattern: /^\/assist\/applications$/, handle: (ctx) => ok(pageOf(matchFilters(store.applications, ctx), ctx)) },
  { method: 'get', pattern: /^\/assist\/(\d+)\/detail$/, handle: (ctx) => {
    const row = findRow(store.assists, ctx.matches[1]) ?? findRow(store.applications, ctx.matches[1]);
    return ok(row ?? null);
  } },
  { method: 'get', pattern: /^\/assist\/(\d+)\/activity$/, handle: (ctx) => {
    const row = findRow(store.assists, ctx.matches[1]) ?? findRow(store.applications, ctx.matches[1]);
    const activity = row?.recordId === undefined ? undefined : findRow(store.activities, row.recordId);
    return ok(activity ?? null);
  } },
  { method: 'get', pattern: /^\/assist\/(\d+)\/approval$/, handle: (ctx) => {
    const row = findRow(store.assists, ctx.matches[1]) ?? findRow(store.applications, ctx.matches[1]);
    const approval = row?.recordId === undefined ? undefined : findRow(store.approvals, row.recordId);
    return ok(approval ?? null);
  } },
  { method: 'get', pattern: /^\/assist\/(\d+)\/task$/, handle: () => ok(null) },
  { method: 'get', pattern: /^\/assist\/(\d+)\/attachments$/, handle: () => ok([]) },
  { method: 'post', pattern: /^\/assist\/(\d+)\/attachments$/, handle: () => ok(true) },
  { method: 'put', pattern: /^\/assist$/, handle: (ctx) => {
    store.assists = replaceRow(store.assists, ctx.body.id, { ...ctx.body, assistTime: nowText() });
    return ok(true);
  } },
  { method: 'post', pattern: /^\/assist\/reapply$/, handle: (ctx) => {
    const source = findRow(store.applications, ctx.body.id);
    store.applications.unshift({
      ...(source ?? { modelName: 'business_activity', recordTitle: '演示申请' }),
      ...ctx.body, id: allocId(), assistStatus: 0, rejectReason: '', assistContent: '', createTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'post', pattern: /^\/assist\/append$/, handle: (ctx) => {
    store.applications.unshift({ ...ctx.body, id: allocId(), assistStatus: 0, createTime: nowText() });
    return ok(true);
  } },

  // 组织与权限
  { method: 'get', pattern: /^\/company-group\/list$/, handle: (ctx) => ok(filterByKeyword(store.groups, ctx, 'groupName')) },
  { method: 'get', pattern: /^\/company-dept\/list$/, handle: (ctx) => {
    let rows = store.depts;
    if (ctx.params.groupId !== undefined && ctx.params.groupId !== '') {
      rows = rows.filter((row) => String(row.groupId) === String(ctx.params.groupId));
    }
    return ok(filterByKeyword(rows, ctx, 'deptName'));
  } },
  { method: 'post', pattern: /^\/company-group$/, handle: (ctx) => {
    store.groups.unshift({ id: allocId(), groupName: ctx.body.groupName ?? '新集团', status: 1, createTime: nowText() });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/company-group$/, handle: (ctx) => {
    store.groups = replaceRow(store.groups, ctx.body.id, ctx.body);
    return ok(true);
  } },
  { method: 'post', pattern: /^\/company-dept$/, handle: (ctx) => {
    const group = ctx.body.groupId === undefined ? undefined : findRow(store.groups, ctx.body.groupId);
    store.depts.unshift({
      id: allocId(), groupId: ctx.body.groupId, groupName: (group?.groupName as string) ?? '',
      deptName: ctx.body.deptName ?? '新部门', status: 1, createTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/company-dept$/, handle: (ctx) => {
    store.depts = replaceRow(store.depts, ctx.body.id, ctx.body);
    return ok(true);
  } },
  { method: 'get', pattern: /^\/role\/list$/, handle: (ctx) => ok(pageOf(store.roles, ctx)) },
  { method: 'get', pattern: /^\/role$/, handle: () => ok(store.roles) },
  { method: 'post', pattern: /^\/role\/add$/, handle: (ctx) => {
    store.roles.unshift({ id: allocId(), roleName: ctx.body.roleName ?? '新角色', createTime: nowText() });
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/role$/, handle: (ctx) => {
    const ids = bodyIds(ctx);
    store.roles = store.roles.filter((row) => !ids.some((id) => String(id) === String(row.id)));
    return ok(true);
  } },
  { method: 'get', pattern: /^\/permission\/list$/, handle: () => ok(permissionCatalog) },
  { method: 'get', pattern: /^\/permission\/auditor$/, handle: () => ok(store.users) },
  { method: 'get', pattern: /^\/permission\/getByRole$/, handle: () => {
    const items = flattenPermissions();
    return ok({
      mainPermissions: items.filter((item) => item.parentPermissionId === undefined),
      subPermissions: items.filter((item) => item.parentPermissionId !== undefined),
    });
  } },
  { method: 'post', pattern: /^\/permission\/addORDeletePermissionsToRole$/, handle: () => ok(true) },
  { method: 'post', pattern: /^\/user\/find$/, handle: (ctx) => ok(pageOf(matchFilters(store.users, ctx), ctx)) },
  { method: 'get', pattern: /^\/user\/options$/, handle: () => ok(store.users) },
  { method: 'post', pattern: /^\/user\/add$/, handle: (ctx) => {
    const role = ctx.body.roleId === undefined ? undefined : findRow(store.roles, ctx.body.roleId);
    store.users.unshift({
      id: allocId(), ...ctx.body, roleName: (role?.roleName as string) ?? '销售',
      status: Number(ctx.body.status ?? 1), createTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'post', pattern: /^\/user\/update$/, handle: (ctx) => {
    store.users = replaceRow(store.users, ctx.body.id, ctx.body);
    return ok(true);
  } },
  { method: 'post', pattern: /^\/user\/handover\/query$/, handle: (ctx) => ok(pageOf(store.handovers, ctx)) },
  { method: 'post', pattern: /^\/user\/handover\/execute$/, handle: () => ok(true) },

  // 内部部门（用户新增时的部门下拉、部门管理页签）
  { method: 'get', pattern: /^\/dept\/list$/, handle: () => ok(store.sysDepts) },
  { method: 'get', pattern: /^\/dept\/all$/, handle: () => ok(store.sysDepts) },
  { method: 'post', pattern: /^\/dept$/, handle: (ctx) => {
    store.sysDepts.unshift({
      id: allocId(), deptName: ctx.body.deptName ?? '新部门', parentId: Number(ctx.body.parentId ?? 0),
      sort: store.sysDepts.length + 1, status: 1, createTime: nowText(),
    });
    return ok(true);
  } },
  { method: 'put', pattern: /^\/dept$/, handle: (ctx) => {
    store.sysDepts = replaceRow(store.sysDepts, ctx.body.id, ctx.body);
    return ok(true);
  } },
  { method: 'delete', pattern: /^\/dept\/(\d+)$/, handle: (ctx) => {
    store.sysDepts = store.sysDepts.filter((row) => String(row.id) !== ctx.matches[1]);
    return ok(true);
  } },

  // 项目文件与公共下载
  { method: 'post', pattern: /^\/project\/file\/query$/, handle: (ctx) => ok(pageOf([], ctx)) },
  { method: 'get', pattern: /^\/project\/file\/list\/opportunity\/(\d+)$/, handle: (ctx) => ok(store.projectFiles.filter((row) => textOf(row.opportunityId) === ctx.matches[1])) },
  { method: 'get', pattern: /^\/project\/file\/list\/contract\/(\d+)$/, handle: (ctx) => ok(store.projectFiles.filter((row) => textOf(row.contractId) === ctx.matches[1])) },
  { method: 'get', pattern: /^\/project\/file\/list\//, handle: () => ok([]) },
  { method: 'post', pattern: /^\/project\/file\/upload/, handle: () => ok(true) },
  { method: 'delete', pattern: /^\/project\/file$/, handle: () => ok(true) },
  { method: 'get', pattern: /^\/public\/attachment\/download$/, handle: () => toBlob(CSV_TEMPLATE) },
];

// ---------------- 拦截与开关 ----------------

let isEnabled = false;
let badge: HTMLDivElement | null = null;

const realAdapter: AxiosAdapter = axios.getAdapter(axiosInstance.defaults.adapter);

function parseBody(config: InternalAxiosRequestConfig): Record<string, unknown> {
  const raw = config.data;
  if (!raw) {
    return {};
  }
  if (typeof FormData !== 'undefined' && raw instanceof FormData) {
    const result: Record<string, unknown> = {};
    raw.forEach((value, key) => {
      if (typeof value === 'string') {
        result[key] = value;
      }
    });
    return result;
  }
  if (typeof raw === 'string') {
    try {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return { ids: parsed };
      }
      return plainObject(parsed) ? parsed : {};
    } catch {
      return {};
    }
  }
  return plainObject(raw) ? raw : {};
}

function tryMock(config: InternalAxiosRequestConfig): AxiosResponse | null {
  // hey-api 调用时 baseURL 为空、完整路径（含 /api 前缀）写在 url 上，统一剥掉前缀再匹配
  const raw = (config.url ?? '').split('?')[0];
  const path = raw.startsWith('/api/') ? raw.slice('/api'.length) : raw;
  const method = (config.method ?? 'get').toLowerCase();
  for (const handler of handlers) {
    if (handler.method !== method) {
      continue;
    }
    const matches = path.match(handler.pattern);
    if (!matches) {
      continue;
    }
    const payload = handler.handle({ matches, params: config.params ?? {}, body: parseBody(config) });
    if (payload instanceof Blob) {
      return blobResponse(config, payload);
    }
    return { data: payload, status: 200, statusText: 'OK', headers: {}, config };
  }
  return null;
}

function showBadge() {
  if (badge) {
    return;
  }
  badge = document.createElement('div');
  badge.textContent = '教程演示数据';
  badge.style.cssText = [
    'position:fixed', 'left:12px', 'bottom:12px', 'z-index:9999', 'pointer-events:none',
    'padding:4px 10px', 'border-radius:9999px', 'background:rgba(15,23,42,0.72)', 'color:#fff',
    'font-size:12px', 'line-height:1.6',
  ].join(';');
  document.body.appendChild(badge);
}

function hideBadge() {
  badge?.remove();
  badge = null;
}

// 模块加载即挂载拦截：未开启教程时直接透传真实适配器，零开销
axiosInstance.defaults.adapter = (config) => {
  if (isEnabled) {
    const mocked = tryMock(config);
    if (mocked) {
      return Promise.resolve(mocked);
    }
  }
  return realAdapter(config);
};

export const TutorialMock = {
  isEnabled() {
    return isEnabled;
  },
  /** 教程开始时调用：重置并启用演示数据（重复调用保持当前会话，避免中途重置） */
  enable() {
    if (isEnabled) {
      return;
    }
    store = buildStore();
    isEnabled = true;
    showBadge();
    void message.info('教程演示模式：以下操作均使用演示数据，不会写入真实业务');
  },
  /** 教程结束时调用：恢复真实网络 */
  disable() {
    if (!isEnabled) {
      return;
    }
    isEnabled = false;
    hideBadge();
    void message.info('已退出教程演示模式');
  },
};
