package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.BusinessActivityContactVO;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.BusinessActivityContactService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class BusinessActivityContactServiceImpl extends ServiceImpl<BusinessActivityContactMapper, BusinessActivityContactEntity> implements BusinessActivityContactService {

    @Autowired
    private BusinessActivityContactMapper businessActivityContactMapper;
    @Autowired
    private UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean create(BusinessActivityContactDTO businessActivityContactDTO) {
        BusinessActivityContactEntity businessActivityContactEntity = new BusinessActivityContactEntity();
        BeanUtils.copyProperties(businessActivityContactDTO, businessActivityContactEntity);

        Long currentId = BaseUnit.getCurrentId();
        businessActivityContactEntity.setCreatorId(currentId);

        return save(businessActivityContactEntity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer deleteByIds(List<Long> idList) {
        if (idList == null || idList.isEmpty()) {
            throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
        }
        return businessActivityContactMapper.deleteBatchIds(idList);
    }

    @Override
    @Transactional
    public Integer updateList(List<BusinessActivityContactDTO> businessActivityContactDTOList) {
        List<BusinessActivityContactEntity> businessActivityContactEntityList = new ArrayList<>();

        for (BusinessActivityContactDTO businessActivityContactDTO : businessActivityContactDTOList) {
            BusinessActivityContactEntity businessActivityContactEntity = new BusinessActivityContactEntity();
            BeanUtils.copyProperties(businessActivityContactDTO, businessActivityContactEntity);
            businessActivityContactEntityList.add(businessActivityContactEntity);
        }

        updateBatchById(businessActivityContactEntityList);
        return businessActivityContactEntityList.size();
    }

    @Override
    public List<BusinessActivityContactVO> getAll(Integer pageNum, Integer pageSize) {
        List<BusinessActivityContactVO> ans = new ArrayList<>();

        Page<BusinessActivityContactEntity> page = new Page<>(pageNum, pageSize);
        Page<BusinessActivityContactEntity> pageResult = page(page, new LambdaQueryWrapper<>());

        for (BusinessActivityContactEntity entity : pageResult.getRecords()) {
            BusinessActivityContactVO vo = new BusinessActivityContactVO();
            BeanUtils.copyProperties(entity, vo);
            ans.add(vo);
        }

        return ans;
    }

    @Override
    public Page<BusinessActivityContactVO> query(Integer pageNum, Integer pageSize, BusinessActivityContactDTO dto) {
        Page<BusinessActivityContactEntity> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<BusinessActivityContactEntity> queryWrapper = new LambdaQueryWrapper<>();
        // 自定义查询条件
        if (dto != null) {
            if (dto.getActivityId() != null) {
                queryWrapper.eq(BusinessActivityContactEntity::getActivityId, dto.getActivityId());
            }
            if (dto.getContactId() != null) {
                queryWrapper.eq(BusinessActivityContactEntity::getContactId, dto.getContactId());
            }
            if (dto.getContactRole() != null && !dto.getContactRole().isEmpty()) {
                queryWrapper.eq(BusinessActivityContactEntity::getContactRole, dto.getContactRole());
            }
        }

        // 默认按创建时间倒序
        queryWrapper.orderByDesc(BusinessActivityContactEntity::getCreateTime);

        Page<BusinessActivityContactEntity> entityPage = page(page, queryWrapper);

        // 转换为VO
        Page<BusinessActivityContactVO> voPage = new Page<>();
        BeanUtils.copyProperties(entityPage, voPage);

        List<BusinessActivityContactVO> voList = new ArrayList<>();
        for (BusinessActivityContactEntity entity : entityPage.getRecords()) {
            BusinessActivityContactVO vo = new BusinessActivityContactVO();
            BeanUtils.copyProperties(entity, vo);

            // 新增：查询创建者姓名
            Long creatorId = entity.getCreatorId();
            if (creatorId != null) {
                // 假设你有用户表的mapper（如userMapper），通过creatorId查询真实姓名
                UserEntity user = userMapper.selectById(creatorId);
                if (user != null) {
                    vo.setCreatorName(user.getRealName());
                } else {
                    vo.setCreatorName("未知用户");
                }
            }
            voList.add(vo);
        }

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public BusinessActivityContactVO getDetailById(Long id) {
        // 参数校验
        if (id == null) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
        }

        BusinessActivityContactEntity entity = getById(id);
        if (entity == null) {
            throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS.getMessage());
        }

        BusinessActivityContactVO vo = new BusinessActivityContactVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
