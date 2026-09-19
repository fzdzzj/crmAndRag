export type TourCategory = 'quick' | 'basic' | 'advanced';

export interface TourStep {
  /** 步骤目标路由（hash 路由 path）；省略时停留在当前页面，
   *  用于无法静态直达的页面（如 /sale/detail/[id]），由用户按上一步引导进入 */
  route?: string;
  /** 页面内锚点，对应目标元素上的 data-tour 属性值 */
  anchor: string;
  title: string;
  content: string | string[];
  /** 放行点击但保留"下一步"：引导用户点击高亮元素（如行内「查看」进入详情页）后自己继续 */
  clickable?: boolean;
  /** 自由操作步骤：保持气泡与高亮，同时放开全页交互，让用户真实填写/提交后再继续 */
  freeInteract?: boolean;
}

export interface Tutorial {
  id: string;
  category: TourCategory;
  title: string;
  description: string;
  steps: TourStep[];
}

export const tourCategories: { key: TourCategory; label: string }[] = [
  { key: 'quick', label: '快速上手' },
  { key: 'basic', label: '基础教程' },
  { key: 'advanced', label: '进阶教程' },
];

export const tutorials: Tutorial[] = [
  {
    id: 'quick-start',
    category: 'quick',
    title: '快速上手：从客户到阶段审批',
    description:
      '跟着引导走完 CRM 核心链路：认识菜单 → 新建客户 → 新增联系人 → 创建销售订单 → 推进商机阶段 → 处理阶段审批。',
    steps: [
      {
        route: '/client/list',
        anchor: 'sidebar-nav',
        title: '认识左侧导航',
        content:
          '菜单按业务域划分（客户、销售、财务、联络任务等），并按你的权限显示。',
      },
      {
        route: '/client/list',
        anchor: 'client-create',
        title: '新建客户',
        content:
          '点击「新建客户」建档：支持表单逐项填写，或用「Excel导入」批量导入；除公司名称外均可后续补充。',
      },
      {
        route: '/client/list',
        anchor: 'client-contacts-tab',
        title: '切换到联系人管理',
        content:
          '联系人都归属在客户公司下。页面切换通过顶部页签完成，点「下一步」自动进入联系人页面。',
      },
      {
        route: '/client/contacts',
        anchor: 'contact-create',
        title: '新增联系人',
        content:
          '点击「新增联系人」为客户公司建档；之后也可在客户列表点击联系人姓名直接查看。',
      },
      {
        route: '/client/contacts',
        anchor: 'menu-sale',
        title: '进入销售管理',
        content:
          '接下来创建销售订单。业务模块通过左侧菜单切换，点「下一步」自动进入销售管理。',
      },
      {
        route: '/sale/list',
        anchor: 'sale-create',
        title: '创建销售订单',
        content:
          '点击「创建销售订单」：选择客户公司后联系人联动过滤，金额需大于 0，且必须指定一位审批人。',
      },
      {
        route: '/sale/list',
        anchor: 'sale-advance',
        title: '推进商机阶段',
        content:
          '商机按阶段逐级推进。点击行内「推进」，上传附件提交审批；提交时还可同时填写协助人申请。',
      },
      {
        route: '/sale/list',
        anchor: 'sale-approval-tab',
        title: '切换到阶段审批',
        content:
          '推进申请提交后由审批人处理。点「下一步」自动进入阶段审批页面。',
      },
      {
        route: '/sale/stage-approval',
        anchor: 'approval-tabs',
        title: '处理阶段审批',
        content:
          [
            '在「待审批」列表中「同意」或「拒绝」。',
            '同意后商机进入下一阶段；全过程可在商机详情追溯。',
            '下一步可学习《新建客户：手填与 Excel 导入》。',
          ],
      },
    ],
  },

  // ---------- 基础教程 ----------
  {
    id: 'basic-create-client',
    category: 'basic',
    title: '新建客户：手填与 Excel 导入',
    description: '打开建档弹窗实际操作：表单直接手填，或下载模板批量导入。',
    steps: [
      {
        route: '/client/list',
        anchor: 'client-create',
        clickable: true,
        title: '打开新建客户弹窗',
        content:
          '点击「客户管理 → 客户列表」右上角高亮的「新建客户」按钮打开建档弹窗，然后点「下一步」看弹窗里的两种填法。',
      },
      {
        anchor: 'client-create-tabs',
        freeInteract: true,
        title: '手填或导入模板（可实际操作）',
        content:
          [
            '「表单创建」页签填写并创建，除公司名称外均可后续补充。',
            '批量建档切到「Excel导入」：先下载模板，按格式填写后上传。',
            '当前为演示数据，完成后点「下一步」结束教程。',
          ],
      },
    ],
  },
  {
    id: 'basic-client-list',
    category: 'basic',
    title: '客户列表：筛选、分组与导出',
    description: '实际操作列头筛选、分组视图与一键导出。',
    steps: [
      {
        route: '/client/list',
        anchor: 'client-table',
        freeInteract: true,
        title: '列头筛选（实际操作：查）',
        content:
          [
            '表头筛选支持客户属性、星级和关键词。',
            '点「名称」筛选图标，输入「北极星」查看结果。',
            '清空关键词并点「确定」还原，再点「下一步」。',
          ],
      },
      {
        route: '/client/list',
        anchor: 'client-group',
        title: '分组视图',
        content:
          '点「按等级分组」客户按星级折叠展示，点「按公司分组」同名公司（多部门记录）归到一组；点「返回列表」还原普通列表。',
      },
      {
        route: '/client/list',
        anchor: 'client-export',
        title: '导出列表',
        content: '点「导出列表」把客户数据导出为 Excel，方便线下分析汇报。',
      },
      {
        route: '/client/list',
        anchor: 'client-recycle',
        title: '回收站',
        content:
          '删除的客户进入回收站而非直接清除，点「回收站」可查看与恢复。详细用法见进阶教程「回收站与批量管理」。',
      },
    ],
  },
  {
    id: 'basic-manage-contacts',
    category: 'basic',
    title: '管理联系人',
    description: '联系人的两种入口：联系人管理页建档、客户列表直达查看。',
    steps: [
      {
        route: '/client/contacts',
        anchor: 'contact-create',
        title: '新增联系人（增）',
        content:
          [
            '在「联系人管理」点「新增联系人」。',
            '必填所属公司、姓名、手机号；可补充家庭信息。',
            '弹窗内也支持 Excel 批量导入。',
          ],
      },
      {
        route: '/client/contacts',
        anchor: 'contact-edit',
        clickable: true,
        title: '编辑联系人（改）',
        content:
          '点击任意一行高亮的「编辑」图标，打开联系人资料弹窗，然后点「下一步」。',
      },
      {
        anchor: 'contact-form-name',
        freeInteract: true,
        title: '实际操作：修改资料',
        content:
          [
            '修改「手机号」「职位」或补充家庭备注。',
            '点「保存」，列表会立即更新。',
            '当前为演示数据，完成后点「下一步」。',
          ],
      },
      {
        route: '/client/list',
        anchor: 'client-table',
        title: '从客户列表直达',
        content:
          '客户列表的「联系人」列展示每个公司下的联系人，点姓名即可查看详情；超过 4 人会折叠为「等 N 人」。',
      },
      {
        route: '/client/contacts',
        anchor: 'contact-batch',
        title: '批量维护（删与恢复）',
        content:
          '点「批量管理」进入勾选模式，可批量移入回收站；页面同样支持回收站恢复与「导出联系人」。',
      },
    ],
  },
  {
    id: 'basic-create-sale',
    category: 'basic',
    title: '创建销售订单',
    description: '实际创建一张演示订单，并认识列表行内操作。',
    steps: [
      {
        route: '/sale/list',
        anchor: 'sale-create',
        freeInteract: true,
        title: '创建销售订单（实际操作：增）',
        content:
          [
            '点「创建销售订单」，选择公司、联系人、审批人。',
            '金额必须大于 0，保存后新订单排在列表第一条。',
          ],
      },
      {
        route: '/sale/list',
        anchor: 'sale-table',
        title: '订单列表操作',
        content:
          '行内可查看、编辑、推进与删除。注意：只有「关闭」阶段的订单才可删除，进行中的商机不允许删除。',
      },
    ],
  },
  {
    id: 'basic-stage-approval',
    category: 'basic',
    title: '阶段推进与审批',
    description: '真实走一遍：发起推进 → 填备注提交 → 审批人处理。',
    steps: [
      {
        route: '/sale/list',
        anchor: 'sale-advance',
        clickable: true,
        title: '发起阶段推进',
        content:
          [
            '商机从「种子」推进到「关闭」，路径会在弹窗中展示。',
            '点击行内「推进」发起一次推进，再点「下一步」。',
          ],
      },
      {
        anchor: 'advance-remark',
        freeInteract: true,
        title: '填写推进申请（实际操作）',
        content:
          [
            '审批备注必填；可上传佐证附件。',
            '可添加协助人，协作目的与要求都要填写。',
            '点「提交」，再点「下一步」处理审批。',
          ],
      },
      {
        route: '/sale/stage-approval',
        anchor: 'approval-tabs',
        title: '进入阶段审批',
        content:
          '「待审批」页签里能看到刚才发起的申请；「所有审批记录」页签保留全部历史。',
      },
      {
        anchor: 'approval-approve',
        freeInteract: true,
        title: '处理审批（实际操作：审）',
        content:
          [
            '点「同意」或「拒绝」，填写审批意见后确定。',
            '同意后回到销售列表，商机阶段前进一级。',
          ],
      },
    ],
  },
  {
    id: 'basic-contact-task',
    category: 'basic',
    title: '联络任务',
    description: '创建跟进任务并跟踪状态与优先级。',
    steps: [
      {
        route: '/contact/task',
        anchor: 'task-create',
        freeInteract: true,
        title: '新增任务（实际操作：增）',
        content:
          [
            '点「新增任务」，关联联系人、销售机会和执行人。',
            '用选择器填写起止时间，保存后列表立即显示。',
          ],
      },
      {
        route: '/contact/task',
        anchor: 'task-table',
        title: '状态与优先级',
        content:
          '任务状态流转：待处理 → 进行中 → 已完成（不再跟进可取消）；优先级分低/中/高/紧急。列头可按类型、状态、优先级筛选。',
      },
    ],
  },
  {
    id: 'basic-business-activity',
    category: 'basic',
    title: '业务活动',
    description: '实际记录一次拜访或交流，它们是推进阶段的依据。',
    steps: [
      {
        route: '/contact/activity',
        anchor: 'activity-create',
        freeInteract: true,
        title: '新增活动（实际操作：增）',
        content:
          [
            '点「新增活动」，填写标题、内容、时间、类型。',
            '关联销售机会；也可关联联络任务并更新状态。',
          ],
      },
      {
        route: '/contact/activity',
        anchor: 'activity-table',
        title: '活动列表',
        content:
          '列表支持按活动类型筛选，行内可上传附件留证；活动会按阶段分组展示在商机详情页。',
      },
    ],
  },
  {
    id: 'basic-contract-finance',
    category: 'basic',
    title: '合同与回款开票',
    description: '商机签约后的合同签订与财务登记。',
    steps: [
      {
        route: '/sale/contract',
        anchor: 'contract-create',
        freeInteract: true,
        title: '快速创建合同（实际操作：增）',
        content:
          [
            '只有「储备项目」「立项签约」订单能签合同。',
            '填合同名称、金额、签约日期，并添加产品明细。',
            '保存后新合同出现在列表。',
          ],
      },
      {
        route: '/finance',
        anchor: 'finance-tabs',
        title: '回款与开票',
        content:
          '进入合同详情页，右上角可「添加回款」「添加开票」；录入后统一在「财务管理」的发票列表 / 回款列表中查看。',
      },
    ],
  },
  {
    id: 'basic-sale-detail',
    category: 'basic',
    title: '商机详情页导览',
    description: '一个商机详情页里有什么：阶段条、活动、文件与审批追溯。',
    steps: [
      {
        route: '/sale/list',
        anchor: 'sale-view',
        clickable: true,
        title: '进入商机详情',
        content:
          '点击订单行内高亮的「查看」图标进入商机详情页，再点「下一步」继续导览。',
      },
      {
        anchor: 'detail-stageflow',
        title: '阶段流程条',
        content: '顶部阶段流程条标出当前所处阶段，商机推进一目了然。',
      },
      {
        anchor: 'detail-activities',
        title: '业务活动',
        content: '活动按阶段分组展示，点「详情」查看每次拜访/交流的完整记录。',
      },
      {
        anchor: 'detail-files',
        title: '项目文件',
        content: '项目文件区集中管理商机相关文档，可按权限上传与下载。',
      },
      {
        anchor: 'detail-records',
        title: '状态变更记录',
        content:
          '每次阶段推进都留痕：旧/目标阶段、审批状态与意见、协助人、附件全程可追溯，点「详情」直达对应审批。',
      },
    ],
  },
  {
    id: 'basic-org-permission',
    category: 'basic',
    title: '组织架构与权限',
    description: '集团部门维护、角色赋权与用户管理。',
    steps: [
      {
        route: '/org',
        anchor: 'org-create-group',
        freeInteract: true,
        title: '维护组织架构（实际操作：增）',
        content:
          '组织按「集团 → 部门」两级维护。点「新增集团」实际建一个；展开集团后点「新增部门」加部门，还可编辑、启用/停用——都是演示数据。',
      },
      {
        route: '/privilege/role',
        anchor: 'role-create',
        title: '新增角色',
        content: '在「权限管理 → 角色管理」点「新增角色」，按岗位创建角色（如销售、财务）。',
      },
      {
        route: '/privilege/role',
        anchor: 'role-table',
        title: '给角色赋权',
        content:
          '点行内「编辑权限」，按模块勾选权限点即可，详见进阶教程「数据范围与角色配置」。',
      },
      {
        route: '/privilege/user',
        anchor: 'user-create',
        freeInteract: true,
        title: '新增用户（实际操作：增）',
        content:
          '点「新增用户」实际建一个：姓名、电话、邮箱，部门与角色下拉都来自演示数据——角色决定其可见菜单与数据范围。',
      },
      {
        route: '/privilege/user',
        anchor: 'user-handover',
        title: '交接记录',
        content:
          '人员变动时可通过工作交接把名下业务移交给接手人，这里可查询历史交接记录。',
      },
    ],
  },

  // ---------- 进阶教程 ----------
  {
    id: 'advanced-assist',
    category: 'advanced',
    title: '协助功能全解',
    description:
      '协作申请的四种用法：创建表单内申请、待我协助处理、我的申请管理（重新申请/追加）、业务详情查看。',
    steps: [
      {
        route: '/sale/list',
        anchor: 'sale-advance',
        title: '入口一：推进时申请协助',
        content:
          '推进商机时，弹窗内「协助人」区可添加协作同事，写明协作目的与协作要求；审批通过后对方即收到协助任务。',
      },
      {
        route: '/contact/activity',
        anchor: 'activity-create',
        title: '入口二：创建表单内申请',
        content:
          '新增业务活动（以及联络任务）的表单底部都有「协助申请」区，同样选择协助人并填写目的与要求。',
      },
      {
        route: '/assist',
        anchor: 'assist-table',
        freeInteract: true,
        title: '入口三：待我协助处理（实际操作）',
        content:
          [
            '顶部下拉按状态筛选，实际处理一条待协助任务。',
            '点「处理」，在「处理」页签填写结果后点「已协助」。',
            '也可驳回/拒绝并说明理由，或上传交付物附件。',
          ],
      },
      {
        route: '/assist-applications',
        anchor: 'assist-app-table',
        title: '入口四：我的申请管理',
        content:
          '我发出的申请在「我的申请」跟踪状态：被驳回可「重新申请」，执行中可「追加协助」申请更多人手。',
      },
      {
        route: '/sale/stage-approval',
        anchor: 'approval-tabs',
        title: '业务详情查看协助',
        content:
          '阶段审批记录和商机详情的变更记录里都有「协助人」列，点标签即可查看每位协助人的状态与进度。',
      },
    ],
  },
  {
    id: 'advanced-recycle-batch',
    category: 'advanced',
    title: '回收站与批量管理',
    description: '客户/联系人的软删除与恢复，以及任务活动的删除差异。',
    steps: [
      {
        route: '/client/list',
        anchor: 'client-batch',
        freeInteract: true,
        title: '批量管理客户（实际操作：删）',
        content:
          [
            '点「批量管理」，勾选一个客户。',
            '点「批量移动到回收站」，再点「下一步」找回。',
          ],
      },
      {
        route: '/client/list',
        anchor: 'client-recycle',
        freeInteract: true,
        title: '客户回收站（实际操作：恢复）',
        content:
          [
            '点「回收站」，行内点「恢复」找回客户。',
            '「批量删除」是彻底清除，不可恢复。',
          ],
      },
      {
        route: '/client/contacts',
        anchor: 'contact-batch',
        freeInteract: true,
        title: '联系人批量操作',
        content:
          [
            '点「批量管理」，勾选后批量移入回收站。',
            '在「回收站」批量恢复；「批量彻底删除」不可恢复。',
          ],
      },
      {
        route: '/contact/task',
        anchor: 'task-batch',
        title: '注意：任务与活动不可恢复',
        content:
          '联络任务与业务活动的删除是物理删除且没有回收站，批量删除前请务必确认。',
      },
    ],
  },
  {
    id: 'advanced-data-scope',
    category: 'advanced',
    title: '数据范围与角色配置',
    description: '用「三档数据范围 + 角色」控制每个人能看到多少数据。',
    steps: [
      {
        route: '/privilege/role',
        anchor: 'role-create',
        title: '从角色出发',
        content: '权限全部通过角色授予：先按岗位规划角色（如销售、销售总监、财务）。',
      },
      {
        route: '/privilege/role',
        anchor: 'role-table',
        title: '三档数据范围',
        content:
          [
            '点「编辑权限」，查看/导出类权限分三档：自己的、标签的、全部。',
            '销售勾「仅查看自己的」，总监勾「查看全部的」。',
            '敏感字段由「隐私信息查看权限」单独控制。',
          ],
      },
      {
        route: '/privilege/user',
        anchor: 'user-create',
        title: '用户绑定角色',
        content:
          '用户通过部门 + 角色获得权限；调整角色权限后，该角色下所有用户立即生效。',
      },
      {
        route: '/privilege/user',
        anchor: 'user-handover',
        title: '离职交接',
        content:
          '人员离职或转岗时，用「交接记录」对应的工作交接功能将其名下业务批量移交给接手人，避免数据悬空。',
      },
    ],
  },
];

export function getTutorialDurationMinutes(tutorial: Tutorial): number {
  return Math.max(1, Math.ceil(tutorial.steps.length * 0.8));
}

export function getRecommendedNextTutorial(current: Tutorial): Tutorial | null {
  const currentIndex = tutorials.findIndex((tutorial) => tutorial.id === current.id);
  const currentCategoryIndex = tourCategories.findIndex(
    (category) => category.key === current.category,
  );
  for (let index = currentIndex + 1; index < tutorials.length; index += 1) {
    if (tutorials[index].category === current.category) {
      return tutorials[index];
    }
  }
  for (
    let categoryIndex = currentCategoryIndex + 1;
    categoryIndex < tourCategories.length;
    categoryIndex += 1
  ) {
    const next = tutorials.find(
      (tutorial) => tutorial.category === tourCategories[categoryIndex].key,
    );
    if (next) {
      return next;
    }
  }
  return null;
}
