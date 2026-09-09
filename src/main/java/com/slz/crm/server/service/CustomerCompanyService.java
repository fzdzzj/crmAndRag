package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.CompanyQueryDTO;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.dto.CustomerMergeDTO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.GroupResultVO;
import org.springframework.transaction.annotation.Transactional;
import com.slz.crm.pojo.vo.GroupResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface CustomerCompanyService {
    /**
     * 添加单个客户公司数据
     *
     * @param customerCompanyDTO 客户公司DTO对象
     * @return 新客户公司 VO，携带新记录 ID，失败返回 null
     */
    CustomerCompanyVO add(CustomerCompanyDTO customerCompanyDTO);

    /**
     * 添加多个客户公司数据
     * @param file 客户公司数据文件
     * @return 成功添加的数量
     */
    int list(MultipartFile file);

    /**
     * 更新多个客户公司数据
     * @param customerCompanyDTOList 客户公司DTO列表
     * @return 成功更新的数量
     */
    Integer updateList(List<CustomerCompanyDTO> customerCompanyDTOList);

    /**
     * 删除或恢复多个客户公司数据
     * @param idList 客户公司id列表
     * @param isRecover true：删除，false：恢复
     * @return 成功删除或恢复的数量
     */
    Integer deleteOrRecoverByIds(List<Long> idList,Boolean isRecover);


    /**
     * 根据ID列表删除多个客户公司数据（永久删除）
     * @param idList 客户公司ID列表
     * @return 成功删除的数量
     */
    Integer deleteByIds(List<Long> idList);

    /**
     * 逻辑删除客户公司数据
     * @param id 客户公司ID
     * @return 是否删除成功
     */
    Boolean deleteById(Long id);

    /**
     * 逻辑删除客户公司及其关联数据
     * @param id 客户公司ID
     * @return 删除成功数量
     */
    Integer logicalDeleteById(Long id);

    /**
     * 批量逻辑删除客户公司及其关联数据
     * @param idList 客户公司ID列表
     * @return 删除成功数量
     */
    Integer batchLogicalDeleteByIds(List<Long> idList);

    /**
     * 根据联系人信息获取其所属公司信息
     *
     * @param id 联系人ID
     * @return 公司信息VO对象
     */
    CustomerCompanyVO getCompanyByCondition(Long id);

    /**
     * 按照指定字段分组查询客户公司
     * @param field 分组字段
     * @return 分组结果VO
     */
    GroupResultVO groupByCustomerCompany(String field);

    /**
     * 导出客户公司数据
     * @return 导出的Excel文件字节数组
     */
    byte[] excel();

    /**
     * 合并客户公司
     * @param customerMergeDTO 合并客户公司DTO对象，包含目标公司ID、被合并公司ID和操作人ID
     * @return 是否合并成功
     */
    Boolean merge(CustomerMergeDTO customerMergeDTO);

    /**
     * 分页获取所有客户公司
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 客户公司VO列表
     */
    Page<CustomerCompanyVO> getAllCompany(Integer pageNum, Integer pageSize);

    /**
     * 获取客户公司详情
     * @param id 客户公司ID
     * @return 客户公司VO对象
     */
    CustomerCompanyVO getCompanyDetail(Long id);

    /**
     * 模糊查询客户公司
     * @param companyName 模糊信息
     * @return 客户公司VO列表
     */
    Page<CustomerCompanyVO> findCompany(String companyName, Integer pageNum, Integer pageSize);

    /**
     * 自定义组合查询
     * @param customerCompanyDTO 自定义查询DTO
     * @return 客户公司VO列表
     */
    Page<CustomerCompanyVO> customQuery(CustomerCompanyDTO customerCompanyDTO, Integer pageNum, Integer pageSize);

    /**
     * 导出客户公司数据模板
     * @return 导出的Excel文件字节数组
     */
    byte[] template();

    /**
     * 通过归属集团模糊查询客户公司
     * @param belongGroup 分组信息
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 客户公司VO列表
     */
    Page<CustomerCompanyVO> findCompanyByGroup(String belongGroup, Integer pageNum, Integer pageSize);
}
