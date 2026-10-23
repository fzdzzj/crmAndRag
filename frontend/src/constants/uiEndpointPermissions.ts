import type { EndpointKey, EndpointPermissionMap } from '@/types/endpointPermission.gen';

/**
 * 契约允许的 UI 权限名取值：裸枚举名或 `模块:枚举名`（历史库运行时 `permissions_name` 带模块前缀），
 * 枚举段必须与该端点契约 `x-required-permission.name` 一致；OPEN 端点只允许登记 `null`（视为放行）。
 * 端点键写错（不在契约里）或权限名与契约不符，都在编译期报错。
 */
export type ContractPermission<K extends EndpointKey> =
  EndpointPermissionMap[K] extends { name: infer N extends string }
    ? N | `${string}:${N}`
    : EndpointPermissionMap[K] extends { status: 'OPEN' }
      ? null
      : never;

/**
 * UI 端点 → 权限名运行期小表的构造器（编译期护栏）。
 *
 * <p>每键校验：键必须是生成物 {@link EndpointKey}（写错即 TS2339/2353），值必须满足 {@link ContractPermission}
 * （权限名与契约不符 / OPEN 配非 null 即 TS2322）。用显式泛型推断而不是 `keyof typeof` 自引用——后者会把键宽化成
 * `string | number | symbol`，条件类型整体塌成 never（本卡实测）。
 */
export function defineUiPermissionTable<
  T extends Partial<{ [K in EndpointKey]: ContractPermission<K> }>,
>(table: T): T {
  return table;
}

/**
 * UI 端点 → 权限名运行期小表（只登记按钮门控真正用到的端点，57 项）。
 *
 * <p>端点由各站点原权限名在生成物 `endpointPermission.gen.ts` 里反查得到（提交说明含逐站点取用依据）；
 * 唯一保留手抄 `allow` 的例外 = `sale/list.page.vue` 的 SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY（契约 0 候选，
 * 见 PermissionSiteAuditTest 白名单）。
 */
const UI = defineUiPermissionTable({
  'DELETE /business/activity': 'sales:SALES_DELETE_BUSINESS_ACTIVITY',
  'DELETE /company': 'customer:CUSTOMER_DELETE_CUSTOMER_COMPANY',
  'DELETE /contact': 'customer:CUSTOMER_DELETE_CONTACT',
  'DELETE /contact/logical/batch': 'customer:CUSTOMER_DELETE_CONTACT',
  'DELETE /contactTask/{id}': 'task:TASK_DELETE_TASK',
  'DELETE /contract': 'sales:SALES_UPDATE_CONTRACT',
  'DELETE /invoice/info': 'finance:FINANCE_DELETE_INVOICE',
  'DELETE /payment/record': 'finance:FINANCE_DELETE_PAYMENT',
  'DELETE /role': 'system:SYSTEM_MANAGE_ROLE',
  'DELETE /sales/{id}': 'sales:SALES_DELETE_SALE_OPPORTUNITY',
  'DELETE /sales/lead/{id}': 'customer:CUSTOMER_DELETE_CUSTOMER_COMPANY',
  'GET /business/activity/{id}': 'sales:SALES_VIEW_BUSINESS_ACTIVITY',
  'GET /company/custom': 'customer:CUSTOMER_QUERY_COMPANY',
  'GET /company/pool': 'customer:CUSTOMER_QUERY_COMPANY',
  'GET /contact/excel': 'customer:CUSTOMER_EXPORT_CONTACT',
  'GET /contact/search': 'customer:CUSTOMER_QUERY_CONTACT',
  'GET /contactTask/{id}': 'task:TASK_VIEW_TASK',
  'GET /contract/{id}': 'sales:SALES_VIEW_CONTRACT',
  'GET /invoice/info/{id}': 'finance:FINANCE_VIEW_INVOICE',
  'GET /payment/record/{id}': 'finance:FINANCE_VIEW_PAYMENT',
  'GET /role/list': 'system:SYSTEM_VIEW_ROLE',
  'GET /sales/lead/{id}': 'customer:CUSTOMER_VIEW_COMPANY',
  'POST /business/activity': 'sales:SALES_CREATE_BUSINESS_ACTIVITY',
  'POST /business/activity/{id}/attachments': 'sales:SALES_UPDATE_BUSINESS_ACTIVITY',
  'POST /company': 'customer:CUSTOMER_ADD_COMPANY',
  'POST /company/pool/assign': 'customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY',
  'POST /company/pool/claim/{id}': 'customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY',
  'POST /company/pool/recycle/trigger': 'customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY',
  'POST /company/pool/release/{id}': 'customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY',
  'POST /contact': 'customer:CUSTOMER_ADD_CONTACT',
  'POST /contactTask/create': 'task:TASK_CREATE_TASK',
  'POST /contract': 'sales:SALES_CREATE_CONTRACT',
  'POST /contract/warning/trigger': 'sales:SALES_UPDATE_CONTRACT',
  'POST /customer/churn/warning/scan': 'customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY',
  'POST /invoice/info': 'finance:FINANCE_RECORD_INVOICE',
  'POST /payment/record': 'finance:FINANCE_RECORD_PAYMENT',
  'POST /permission/addORDeletePermissionsToRole': 'system:SYSTEM_ASSIGN_PERMISSION',
  'POST /role/add': 'system:SYSTEM_MANAGE_ROLE',
  'POST /sales': 'sales:SALES_CREATE_SALE_OPPORTUNITY',
  'POST /sales/lead': 'customer:CUSTOMER_ADD_COMPANY',
  'POST /sales/lead/convert': 'customer:CUSTOMER_ADD_COMPANY',
  'POST /sales/stage/approval': 'sales:SALES_PROGRESS_SALE_OPPORTUNITY_STAGE',
  'POST /user/add': 'system:SYSTEM_CREATE_USER',
  'POST /user/find': 'system:SYSTEM_VIEW_USER',
  'POST /user/handover/execute': 'system:SYSTEM_UPDATE_USER',
  'POST /user/update': 'system:SYSTEM_UPDATE_USER',
  'PUT /business/activity': 'sales:SALES_UPDATE_BUSINESS_ACTIVITY',
  'PUT /company': 'customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY',
  'PUT /company/recover': 'customer:CUSTOMER_RECOVER_CUSTOMER_COMPANY',
  'PUT /contact': 'customer:CUSTOMER_UPDATE_CONTACT',
  'PUT /contact/recover': 'customer:CUSTOMER_RECOVER_CONTACT',
  'PUT /contactTask/update': 'task:TASK_UPDATE_TASK',
  'PUT /contract': 'sales:SALES_UPDATE_CONTRACT',
  'PUT /invoice/info': 'finance:FINANCE_EDIT_INVOICE',
  'PUT /payment/record': 'finance:FINANCE_EDIT_PAYMENT',
  'PUT /sales': 'sales:SALES_UPDATE_SALE_OPPORTUNITY',
  'PUT /sales/lead': 'customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY',
});

/** 端点 → 权限名的任意运行期查表形状（解析器/测试用，OPEN 登记为 null）。 */
export type PermissionLookupTable = { [K in EndpointKey]?: string | null };

export default UI;
