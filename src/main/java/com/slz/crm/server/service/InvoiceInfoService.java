package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.InvoiceInfoDTO;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import java.util.List;

/** 开票信息服务接口 */
public interface InvoiceInfoService {

  /**
   * 创建开票信息
   *
   * @param dto 开票信息DTO
   * @return 新开票信息 VO，携带新记录 ID，失败返回 null
   */
  InvoiceInfoVO create(InvoiceInfoDTO dto);

  /**
   * 更新开票信息
   *
   * @param dto 开票信息DTO
   * @return 是否更新成功
   */
  Boolean update(InvoiceInfoDTO dto);

  /**
   * 删除开票信息
   *
   * @param idList 开票信息ID列表
   * @return 删除数量
   */
  Integer deleteByIds(List<Long> idList);

  /**
   * 根据ID查询开票信息详情
   *
   * @param id 开票信息ID
   * @return 开票信息详情
   */
  InvoiceInfoVO getDetailById(Long id);

  /**
   * 分页查询开票信息
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @param dto 查询条件DTO
   * @return 开票信息分页结果
   */
  Page<InvoiceInfoVO> queryPage(Integer pageNum, Integer pageSize, InvoiceInfoDTO dto);

  /**
   * 根据合同ID查询开票信息列表
   *
   * @param contractId 合同ID
   * @return 开票信息列表
   */
  List<InvoiceInfoVO> listByContractId(Long contractId);

  /**
   * 根据回款ID查询开票信息列表
   *
   * @param paymentId 回款ID
   * @return 开票信息列表
   */
  List<InvoiceInfoVO> listByPaymentId(Long paymentId);

  /**
   * 作废发票
   *
   * @param id 开票信息ID
   * @return 是否作废成功
   */
  Boolean voidInvoice(Long id);
}
