package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.slz.crm.pojo.dto.UserHandoverDTO;
import com.slz.crm.pojo.dto.UserHandoverQueryDTO;
import com.slz.crm.pojo.entity.UserHandoverEntity;
import com.slz.crm.pojo.vo.HandoverStatisticsVO;
import com.slz.crm.pojo.vo.UserHandoverVO;
import java.util.List;

/** 用户交接服务接口 */
public interface UserHandoverService extends IService<UserHandoverEntity> {

  /**
   * 执行用户离职交接 将离职用户的任务、客户、销售机会全部转移给接收用户
   *
   * @param dto 交接参数
   * @return 交接记录VO
   */
  UserHandoverVO executeHandover(UserHandoverDTO dto);

  /**
   * 查询交接记录
   *
   * @param queryDTO 查询参数
   * @return 分页结果
   */
  Page<UserHandoverVO> queryHandoverRecords(UserHandoverQueryDTO queryDTO);

  /**
   * 获取用户待交接资源统计
   *
   * @param userId 用户ID
   * @return 各类型资源统计列表
   */
  List<HandoverStatisticsVO> getHandoverStatistics(Long userId);
}
