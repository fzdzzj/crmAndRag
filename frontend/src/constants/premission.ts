const res = {
  "code": 1,
  "msg": null,
  "data": {
    "客户管理模块权限": {
      "mainPermissions": [
        {
          "id": 101,
          "permissionsName": "customer:CUSTOMER_EXCEL_ADD",
          "permissionsDesc": "通过EXCEL添加数据"
        },
        {
          "id": 102,
          "permissionsName": "customer:CUSTOMER_UPDATE_CONTACT",
          "permissionsDesc": "更新联系人"
        },
        {
          "id": 103,
          "permissionsName": "customer:CUSTOMER_ADD_CONTACT",
          "permissionsDesc": "新增联系人"
        },
        {
          "id": 104,
          "permissionsName": "customer:CUSTOMER_DELETE_CONTACT",
          "permissionsDesc": "删除联系人"
        },
        {
          "id": 105,
          "permissionsName": "customer:CUSTOMER_RECOVER_CONTACT",
          "permissionsDesc": "恢复联系人"
        },
        {
          "id": 106,
          "permissionsName": "customer:CUSTOMER_QUERY_CONTACT",
          "permissionsDesc": "查询联系人"
        },
        {
          "id": 107,
          "permissionsName": "customer:CUSTOMER_EXPORT_CONTACT",
          "permissionsDesc": "导出联系人"
        },
        {
          "id": 111,
          "permissionsName": "customer:CUSTOMER_ADD_COMPANY",
          "permissionsDesc": "新增客户公司"
        },
        {
          "id": 113,
          "permissionsName": "customer:CUSTOMER_UPDATE_CUSTOMER_COMPANY",
          "permissionsDesc": "更新客户公司"
        },
        {
          "id": 114,
          "permissionsName": "customer:CUSTOMER_DELETE_CUSTOMER_COMPANY",
          "permissionsDesc": "删除客户公司"
        },
        {
          "id": 115,
          "permissionsName": "customer:CUSTOMER_RECOVER_CUSTOMER_COMPANY",
          "permissionsDesc": "恢复客户公司"
        },
        {
          "id": 116,
          "permissionsName": "customer:CUSTOMER_QUERY_COMPANY",
          "permissionsDesc": "查询客户公司"
        },
        {
          "id": 117,
          "permissionsName": "customer:CUSTOMER_VIEW_COMPANY",
          "permissionsDesc": "查看客户公司"
        },
        {
          "id": 118,
          "permissionsName": "customer:CUSTOMER_EXPORT_CUSTOMER_COMPANY",
          "permissionsDesc": "导出客户公司"
        },
        {
          "id": 119,
          "permissionsName": "customer:CUSTOMER_MERGE_COMPANY",
          "permissionsDesc": "合并客户公司"
        },
        {
          "id": 1061,
          "permissionsName": "customer:CUSTOMER_QUERY_CONTACT_ONLY_MY",
          "permissionsDesc": "查询联系人 - 仅查看自己的"
        },
        {
          "id": 1062,
          "permissionsName": "customer:CUSTOMER_QUERY_CONTACT_TAGE",
          "permissionsDesc": "查询联系人 - 查看标签的"
        },
        {
          "id": 1063,
          "permissionsName": "customer:CUSTOMER_QUERY_CONTACT_ALL",
          "permissionsDesc": "查询联系人 - 查看全部的"
        },
        {
          "id": 1071,
          "permissionsName": "customer:CUSTOMER_EXPORT_CONTACT_ONLY_MY",
          "permissionsDesc": "导出联系人 - 仅导出自己的"
        },
        {
          "id": 1072,
          "permissionsName": "customer:CUSTOMER_EXPORT_CONTACT_TAGE",
          "permissionsDesc": "导出联系人 - 导出标签的"
        },
        {
          "id": 1073,
          "permissionsName": "customer:CUSTOMER_EXPORT_CONTACT_ALL",
          "permissionsDesc": "导出联系人 - 导出全部的"
        },
        {
          "id": 1161,
          "permissionsName": "customer:CUSTOMER_QUERY_COMPANY_ONLY_MY",
          "permissionsDesc": "查询客户公司 - 仅查看自己的"
        },
        {
          "id": 1162,
          "permissionsName": "customer:CUSTOMER_QUERY_COMPANY_TAGE",
          "permissionsDesc": "查询客户公司 - 查看标签的"
        },
        {
          "id": 1163,
          "permissionsName": "customer:CUSTOMER_QUERY_COMPANY_ALL",
          "permissionsDesc": "查询客户公司 - 查看全部的"
        },
        {
          "id": 1181,
          "permissionsName": "customer:CUSTOMER_EXPORT_CUSTOMER_COMPANY_ONLY_MY",
          "permissionsDesc": "导出客户公司 - 仅导出自己的"
        },
        {
          "id": 1182,
          "permissionsName": "customer:CUSTOMER_EXPORT_CUSTOMER_COMPANY_TAGE",
          "permissionsDesc": "导出客户公司 - 导出标签的"
        },
        {
          "id": 1183,
          "permissionsName": "customer:CUSTOMER_EXPORT_CUSTOMER_COMPANY_ALL",
          "permissionsDesc": "导出客户公司 - 导出全部的"
        }
      ],
      "subPermissions": [
        {
          "id": 1171,
          "permissionsName": "customer:CUSTOMER_VIEW_COMPANY_ONLY_MY",
          "permissionsDesc": "查看客户公司 - 仅查看自己的",
          "parentPermissionId": 117
        },
        {
          "id": 1172,
          "permissionsName": "customer:CUSTOMER_VIEW_COMPANY_TAGE",
          "permissionsDesc": "查看客户公司 - 查看标签的",
          "parentPermissionId": 117
        },
        {
          "id": 1173,
          "permissionsName": "customer:CUSTOMER_VIEW_COMPANY_ALL",
          "permissionsDesc": "查看客户公司 - 查看全部的",
          "parentPermissionId": 117
        }
      ]
    },
    "销售管理模块权限": {
      "mainPermissions": [
        {
          "id": 201,
          "permissionsName": "sales:SALES_CREATE_SALE_OPPORTUNITY",
          "permissionsDesc": "创建销售机会"
        },
        {
          "id": 202,
          "permissionsName": "sales:SALES_UPDATE_SALE_OPPORTUNITY",
          "permissionsDesc": "修改销售机会"
        },
        {
          "id": 203,
          "permissionsName": "sales:SALES_DELETE_SALE_OPPORTUNITY",
          "permissionsDesc": "删除销售机会"
        },
        {
          "id": 204,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY",
          "permissionsDesc": "查看销售机会"
        },
        {
          "id": 206,
          "permissionsName": "sales:SALES_APPEND_ORDER",
          "permissionsDesc": "追加订单"
        },
        {
          "id": 210,
          "permissionsName": "sales:SALES_UPDATE_ORDER",
          "permissionsDesc": "更新订单"
        },
        {
          "id": 211,
          "permissionsName": "sales:SALES_VIEW_ORDER",
          "permissionsDesc": "查看订单"
        },
        {
          "id": 212,
          "permissionsName": "sales:SALES_CREATE_CONTRACT",
          "permissionsDesc": "创建合同"
        },
        {
          "id": 214,
          "permissionsName": "sales:SALES_UPDATE_CONTRACT",
          "permissionsDesc": "更新合同"
        },
        {
          "id": 215,
          "permissionsName": "sales:SALES_VIEW_CONTRACT",
          "permissionsDesc": "查看合同"
        },
        {
          "id": 217,
          "permissionsName": "sales:SALES_PROGRESS_SALE_OPPORTUNITY_STAGE",
          "permissionsDesc": "推进销售机会阶段"
        },
        {
          "id": 218,
          "permissionsName": "sales:SALES_APPROVE_STAGE_ADVANCE",
          "permissionsDesc": "审批推进阶段"
        },
        {
          "id": 219,
          "permissionsName": "sales:SALES_DELETE_STAGE_ADVANCE",
          "permissionsDesc": "删除推进请求"
        },
        {
          "id": 220,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY_STAGE",
          "permissionsDesc": "查看销售机会阶段"
        },
        {
          "id": 222,
          "permissionsName": "sales:SALES_CREATE_BUSINESS_ACTIVITY",
          "permissionsDesc": "创建商业活动"
        },
        {
          "id": 223,
          "permissionsName": "sales:SALES_UPDATE_BUSINESS_ACTIVITY",
          "permissionsDesc": "更新商业活动"
        },
        {
          "id": 224,
          "permissionsName": "sales:SALES_DELETE_BUSINESS_ACTIVITY",
          "permissionsDesc": "删除商业活动"
        },
        {
          "id": 225,
          "permissionsName": "sales:SALES_VIEW_BUSINESS_ACTIVITY",
          "permissionsDesc": "查看商业活动"
        },
        {
          "id": 226,
          "permissionsName": "sales:SALES_UPLOAD_PROJECT_FILE",
          "permissionsDesc": "上传项目文件"
        },
        {
          "id": 228,
          "permissionsName": "sales:SALES_DELETE_PROJECT_FILE",
          "permissionsDesc": "删除项目文件"
        },
        {
          "id": 229,
          "permissionsName": "sales:SALES_VIEW_PROJECT_FILE",
          "permissionsDesc": "查看项目文件"
        },
        {
          "id": 2291,
          "permissionsName": "sales:SALES_VIEW_PROJECT_FILE_ONLY_MY",
          "permissionsDesc": "查看项目文件 - 仅查看自己的"
        },
        {
          "id": 2292,
          "permissionsName": "sales:SALES_VIEW_PROJECT_FILE_TAGE",
          "permissionsDesc": "查看项目文件 - 查看标签的"
        },
        {
          "id": 2293,
          "permissionsName": "sales:SALES_VIEW_PROJECT_FILE_ALL",
          "permissionsDesc": "查看项目文件 - 查看全部的"
        }
      ],
      "subPermissions": [
        {
          "id": 2041,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY",
          "permissionsDesc": "查看销售机会 - 仅查看自己的",
          "parentPermissionId": 204
        },
        {
          "id": 2042,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY_TAGE",
          "permissionsDesc": "查看销售机会 - 查看标签的",
          "parentPermissionId": 204
        },
        {
          "id": 2043,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY_ALL",
          "permissionsDesc": "查看销售机会 - 查看全部的",
          "parentPermissionId": 204
        },
        {
          "id": 2111,
          "permissionsName": "sales:SALES_VIEW_ORDER_ONLY_MY",
          "permissionsDesc": "查看订单 - 仅查看自己的",
          "parentPermissionId": 211
        },
        {
          "id": 2112,
          "permissionsName": "sales:SALES_VIEW_ORDER_TAGE",
          "permissionsDesc": "查看订单 - 查看标签的",
          "parentPermissionId": 211
        },
        {
          "id": 2113,
          "permissionsName": "sales:SALES_VIEW_ORDER_ALL",
          "permissionsDesc": "查看订单 - 查看全部的",
          "parentPermissionId": 211
        },
        {
          "id": 2151,
          "permissionsName": "sales:SALES_VIEW_CONTRACT_ONLY_MY",
          "permissionsDesc": "查看合同 - 仅查看自己的",
          "parentPermissionId": 215
        },
        {
          "id": 2152,
          "permissionsName": "sales:SALES_VIEW_CONTRACT_TAGE",
          "permissionsDesc": "查看合同 - 查看标签的",
          "parentPermissionId": 215
        },
        {
          "id": 2153,
          "permissionsName": "sales:SALES_VIEW_CONTRACT_ALL",
          "permissionsDesc": "查看合同 - 查看全部的",
          "parentPermissionId": 215
        },
        {
          "id": 2201,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY_STAGE_ONLY_MY",
          "permissionsDesc": "查看销售机会阶段 - 仅查看自己的",
          "parentPermissionId": 220
        },
        {
          "id": 2202,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY_STAGE_TAGE",
          "permissionsDesc": "查看销售机会阶段 - 查看标签的",
          "parentPermissionId": 220
        },
        {
          "id": 2203,
          "permissionsName": "sales:SALES_VIEW_SALE_OPPORTUNITY_STAGE_ALL",
          "permissionsDesc": "查看销售机会阶段 - 查看全部的",
          "parentPermissionId": 220
        },
        {
          "id": 2251,
          "permissionsName": "sales:SALES_VIEW_BUSINESS_ACTIVITY_ONLY_MY",
          "permissionsDesc": "查看商业活动 - 仅查看自己的",
          "parentPermissionId": 225
        },
        {
          "id": 2252,
          "permissionsName": "sales:SALES_VIEW_BUSINESS_ACTIVITY_TAGE",
          "permissionsDesc": "查看商业活动 - 查看标签的",
          "parentPermissionId": 225
        },
        {
          "id": 2253,
          "permissionsName": "sales:SALES_VIEW_BUSINESS_ACTIVITY_ALL",
          "permissionsDesc": "查看商业活动 - 查看全部的",
          "parentPermissionId": 225
        }
      ]
    },
    "财务管理模块权限": {
      "mainPermissions": [
        {
          "id": 301,
          "permissionsName": "finance:FINANCE_RECORD_PAYMENT",
          "permissionsDesc": "录入回款"
        },
        {
          "id": 302,
          "permissionsName": "finance:FINANCE_EDIT_PAYMENT",
          "permissionsDesc": "编辑回款"
        },
        {
          "id": 303,
          "permissionsName": "finance:FINANCE_DELETE_PAYMENT",
          "permissionsDesc": "删除回款"
        },
        {
          "id": 304,
          "permissionsName": "finance:FINANCE_VIEW_PAYMENT",
          "permissionsDesc": "查看回款"
        },
        {
          "id": 306,
          "permissionsName": "finance:FINANCE_RECORD_INVOICE",
          "permissionsDesc": "记录开票"
        },
        {
          "id": 307,
          "permissionsName": "finance:FINANCE_EDIT_INVOICE",
          "permissionsDesc": "编辑开票"
        },
        {
          "id": 308,
          "permissionsName": "finance:FINANCE_DELETE_INVOICE",
          "permissionsDesc": "删除开票"
        },
        {
          "id": 309,
          "permissionsName": "finance:FINANCE_VIEW_INVOICE",
          "permissionsDesc": "查看开票"
        },
        {
          "id": 3091,
          "permissionsName": "finance:FINANCE_VIEW_INVOICE_ONLY_MY",
          "permissionsDesc": "查看开票 - 仅查看自己的"
        },
        {
          "id": 3092,
          "permissionsName": "finance:FINANCE_VIEW_INVOICE_TAGE",
          "permissionsDesc": "查看开票 - 查看标签的"
        },
        {
          "id": 3093,
          "permissionsName": "finance:FINANCE_VIEW_INVOICE_ALL",
          "permissionsDesc": "查看开票 - 查看全部的"
        }
      ],
      "subPermissions": [
        {
          "id": 3041,
          "permissionsName": "finance:FINANCE_VIEW_PAYMENT_ONLY_MY",
          "permissionsDesc": "查看回款 - 仅查看自己的",
          "parentPermissionId": 304
        },
        {
          "id": 3042,
          "permissionsName": "finance:FINANCE_VIEW_PAYMENT_TAGE",
          "permissionsDesc": "查看回款 - 查看标签的",
          "parentPermissionId": 304
        },
        {
          "id": 3043,
          "permissionsName": "finance:FINANCE_VIEW_PAYMENT_ALL",
          "permissionsDesc": "查看回款 - 查看全部的",
          "parentPermissionId": 304
        }
      ]
    },
    "联络任务模块权限": {
      "mainPermissions": [
        {
          "id": 401,
          "permissionsName": "task:TASK_CREATE_TASK",
          "permissionsDesc": "创建任务"
        },
        {
          "id": 402,
          "permissionsName": "task:TASK_UPDATE_TASK",
          "permissionsDesc": "更新任务"
        },
        {
          "id": 403,
          "permissionsName": "task:TASK_DELETE_TASK",
          "permissionsDesc": "删除任务"
        },
        {
          "id": 404,
          "permissionsName": "task:TASK_VIEW_TASK",
          "permissionsDesc": "查看任务"
        }
      ],
      "subPermissions": [
        {
          "id": 4041,
          "permissionsName": "task:TASK_VIEW_TASK_ONLY_MY",
          "permissionsDesc": "查看任务 - 仅查看自己的",
          "parentPermissionId": 404
        },
        {
          "id": 4042,
          "permissionsName": "task:TASK_VIEW_TASK_TAGE",
          "permissionsDesc": "查看任务 - 查看标签的",
          "parentPermissionId": 404
        },
        {
          "id": 4043,
          "permissionsName": "task:TASK_VIEW_TASK_ALL",
          "permissionsDesc": "查看任务 - 查看全部的",
          "parentPermissionId": 404
        }
      ]
    },
    "统计报表模块权限": {
      "mainPermissions": [
        {
          "id": 501,
          "permissionsName": "report:REPORT_VIEW_REPORT",
          "permissionsDesc": "查看报表"
        },
        {
          "id": 502,
          "permissionsName": "report:REPORT_GENERATE_REPORT",
          "permissionsDesc": "生成报表"
        },
        {
          "id": 503,
          "permissionsName": "report:REPORT_EXPORT_REPORT",
          "permissionsDesc": "导出报表"
        },
        {
          "id": 504,
          "permissionsName": "report:REPORT_MANAGE_TEMPLATE",
          "permissionsDesc": "管理报表模板"
        }
      ],
      "subPermissions": []
    },
    "权限管理模块权限": {
      "mainPermissions": [
        {
          "id": 601,
          "permissionsName": "system:SYSTEM_CREATE_USER",
          "permissionsDesc": "创建用户"
        },
        {
          "id": 602,
          "permissionsName": "system:SYSTEM_VIEW_USER",
          "permissionsDesc": "查看用户"
        },
        {
          "id": 603,
          "permissionsName": "system:SYSTEM_UPDATE_USER",
          "permissionsDesc": "修改用户"
        },
        {
          "id": 604,
          "permissionsName": "system:SYSTEM_VIEW_ROLE",
          "permissionsDesc": "查看角色"
        },
        {
          "id": 605,
          "permissionsName": "system:SYSTEM_MANAGE_ROLE",
          "permissionsDesc": "管理角色"
        },
        {
          "id": 606,
          "permissionsName": "system:SYSTEM_ASSIGN_PERMISSION",
          "permissionsDesc": "分配权限"
        },
        {
          "id": 6021,
          "permissionsName": "system:SYSTEM_VIEW_USER_ONLY_MY",
          "permissionsDesc": "查看用户 - 仅查看自己的"
        },
        {
          "id": 6022,
          "permissionsName": "system:SYSTEM_VIEW_USER_TAGE",
          "permissionsDesc": "查看用户 - 查看标签的"
        },
        {
          "id": 6023,
          "permissionsName": "system:SYSTEM_VIEW_USER_ALL",
          "permissionsDesc": "查看用户 - 查看全部的"
        },
        {
          "id": 6041,
          "permissionsName": "system:SYSTEM_VIEW_ROLE_ONLY_MY",
          "permissionsDesc": "查看角色 - 仅查看自己的"
        },
        {
          "id": 6042,
          "permissionsName": "system:SYSTEM_VIEW_ROLE_TAGE",
          "permissionsDesc": "查看角色 - 查看标签的"
        },
        {
          "id": 6043,
          "permissionsName": "system:SYSTEM_VIEW_ROLE_ALL",
          "permissionsDesc": "查看角色 - 查看全部的"
        }
      ],
      "subPermissions": []
    },
    "隐私信息查看权限": {
      "mainPermissions": [
        {
          "id": 701,
          "permissionsName": "project:PRIVACY_PHONE_VIEW",
          "permissionsDesc": "查看电话"
        },
        {
          "id": 702,
          "permissionsName": "project:PRIVACY_EMAIL_VIEW",
          "permissionsDesc": "查看邮箱"
        },
        {
          "id": 703,
          "permissionsName": "project:PRIVACY_TOTAL_VIEW",
          "permissionsDesc": "查看金额"
        }
      ],
      "subPermissions": []
    }
  }
} as const;
export default res.data