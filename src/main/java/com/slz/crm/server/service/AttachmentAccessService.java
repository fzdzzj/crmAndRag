package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;

/**
 * 附件下载前的记录级授权。
 *
 * <p>下载地址本身不是业务权限快照；每次实际下载都必须重新校验源业务记录。
 */
public interface AttachmentAccessService {

  /** 普通附件入口的记录级读取权限。 只判断管理员、业务相关人和预留的部门上司范围，不判断协助关系。 */
  boolean canReadAttachments(String modelName, Long recordId, Long userId);

  /** 普通附件入口的记录级写权限（上传/删除共用）。 只判断管理员、业务相关人和预留的部门上司范围；协助来源写入必须走专用接口。 */
  boolean canWriteAttachments(String modelName, Long recordId, Long userId);

  /**
   * 校验当前用户是否可以读取指定附件所属业务记录。
   *
   * @param attachment 附件元数据
   * @param userId 当前登录用户 ID
   */
  void assertCanRead(ApprovalAttachmentEntity attachment, Long userId);

  /** 校验实时协助来源附件下载，必须绑定单一待协助记录。 */
  void assertCanReadAssistSource(ApprovalAttachmentEntity attachment, Long assistId, Long userId);

  /**
   * 校验终态协助快照中的历史附件下载权限。
   *
   * <p>该方法只适用于已结束协助，且附件必须真实出现在该协助的冻结快照中。
   */
  void assertCanReadHistorical(ApprovalAttachmentEntity attachment, Long assistId, Long userId);

  /**
   * 校验当前用户是否可以读取项目文件（下载/列表/生成链接统一入口）。
   *
   * <p>按文件的归属维度（业务活动/销售机会/合同/订单）做记录级读取授权； 不含任何归属维度的独立上传文件仅上传人本人与超管可见。
   */
  boolean canReadProjectFile(ProjectFileEntity file, Long userId);
}
