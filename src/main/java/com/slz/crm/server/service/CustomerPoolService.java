package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.CustomerPoolAssignDTO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerPoolLogVO;
import java.util.List;

/**
 * 客户公海池业务服务（add-customer-public-sea-pool 任务 2.x）。
 *
 * <p>职责：公海客户分页查询、业务员自主认领（私海 50 配额保护）、主动退回、主管分派、流转日志查询。 每次流转在事务提交后经 {@code
 * CustomerSemanticHookSupport} 异步同步语义向量，保证 Qdrant 权限锚与所有者一致。
 */
public interface CustomerPoolService {

  /**
   * 公海池客户分页查询（pool_status=1 AND is_deleted=0，专有过滤不被私海 DataScope 阻隔）。
   *
   * @param pageNum 页码
   * @param pageSize 每页条数
   * @param companyName 公司名（可选模糊）
   * @param industry 行业（可选精确）
   * @param grade 客户等级（可选精确）
   * @return 公海客户分页 VO
   */
  Page<CustomerCompanyVO> queryPool(
      Integer pageNum, Integer pageSize, String companyName, String industry, Integer grade);

  /**
   * 业务员自主认领公海客户（私海保有量满 50 抛配额异常；成功后更新状态/负责人、插入 CLAIM 日志、事务后异步同步向量）。
   *
   * @param id 公海客户公司 ID
   * @return 是否认领成功
   */
  Boolean claim(Long id);

  /**
   * 业务员主动退回客户至公海（仅负责人或主管可退回；更新为公海状态、记录退回原因、插入 RELEASE 日志、事务后异步同步向量）。
   *
   * @param id 客户公司 ID
   * @param reason 退回原因
   * @return 是否退回成功
   */
  Boolean release(Long id, String reason);

  /**
   * 主管分派公海客户给指定销售人员（校验目标私海配额、设置新负责人并重置公海状态、插入 ASSIGN 日志、事务后异步同步向量）。
   *
   * @param dto 分派请求（companyId / targetUserId / reason）
   * @return 是否分派成功
   */
  Boolean assign(CustomerPoolAssignDTO dto);

  /**
   * 查询指定客户的全生命周期公海流转日志（按时间倒序）。
   *
   * @param companyId 客户公司 ID
   * @return 流转日志 VO 列表
   */
  List<CustomerPoolLogVO> queryLogs(Long companyId);
}
