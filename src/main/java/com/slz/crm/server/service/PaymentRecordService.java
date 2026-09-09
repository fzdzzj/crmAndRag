package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.PaymentRecordDTO;
import com.slz.crm.pojo.vo.PaymentRecordVO;

import java.util.List;

/**
 * 回款记录服务接口
 */
public interface PaymentRecordService {

    /**
     * 创建回款记录
     * @param dto 回款记录DTO
     * @return 新回款记录 VO，携带新记录 ID，失败返回 null
     */
    PaymentRecordVO create(PaymentRecordDTO dto);

    /**
     * 更新回款记录
     * @param dto 回款记录DTO
     * @return 是否更新成功
     */
    Boolean update(PaymentRecordDTO dto);

    /**
     * 删除回款记录
     * @param idList 回款记录ID列表
     * @return 删除数量
     */
    Integer deleteByIds(List<Long> idList);

    /**
     * 根据ID查询回款记录详情
     * @param id 回款记录ID
     * @return 回款记录详情
     */
    PaymentRecordVO getDetailById(Long id);

    /**
     * 分页查询回款记录
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param dto 查询条件DTO
     * @return 回款记录分页结果
     */
    Page<PaymentRecordVO> queryPage(Integer pageNum, Integer pageSize, PaymentRecordDTO dto);

    /**
     * 根据合同ID查询回款记录列表
     * @param contractId 合同ID
     * @return 回款记录列表
     */
    List<PaymentRecordVO> listByContractId(Long contractId);

    /**
     * 根据订单明细ID查询回款记录列表
     * @param orderItemId 订单明细ID
     * @return 回款记录列表
     */
    List<PaymentRecordVO> listByOrderItemId(Long orderItemId);

    /**
     * 确认回款
     * @param id 回款记录ID
     * @return 是否确认成功
     */
    Boolean confirmPayment(Long id);
}
