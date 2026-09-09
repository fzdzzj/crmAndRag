package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.pojo.vo.BusinessActivityUserVO;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.service.BusinessActivityUserService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class BusinessActivityUserServiceImpl extends ServiceImpl<BusinessActivityUserMapper, BusinessActivityUserEntity> implements BusinessActivityUserService {

    @Autowired
    private BusinessActivityUserMapper businessActivityUserMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean create(BusinessActivityUserDTO businessActivityUserDTO) {
        BusinessActivityUserEntity businessActivityUserEntity = new BusinessActivityUserEntity();
        BeanUtils.copyProperties(businessActivityUserDTO, businessActivityUserEntity);

        Long currentId = BaseUnit.getCurrentId();
        businessActivityUserEntity.setCreatorId(currentId);

        return save(businessActivityUserEntity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer deleteByIds(List<Long> idList) {
        if (idList == null || idList.isEmpty()) {
            throw new BaseException(ErrorCode.PARAM_EMPTY.getMessage());
        }
        return businessActivityUserMapper.deleteBatchIds(idList);
    }

    @Override
    @Transactional
    public Integer updateList(List<BusinessActivityUserDTO> businessActivityUserDTOList) {
        List<BusinessActivityUserEntity> businessActivityUserEntityList = new ArrayList<>();

        for (BusinessActivityUserDTO businessActivityUserDTO : businessActivityUserDTOList) {
            BusinessActivityUserEntity businessActivityUserEntity = new BusinessActivityUserEntity();
            BeanUtils.copyProperties(businessActivityUserDTO, businessActivityUserEntity);
            businessActivityUserEntityList.add(businessActivityUserEntity);
        }

        updateBatchById(businessActivityUserEntityList);
        return businessActivityUserEntityList.size();
    }

    @Override
    public List<BusinessActivityUserVO> getAll(Integer pageNum, Integer pageSize) {
        List<BusinessActivityUserVO> ans = new ArrayList<>();

        Page<BusinessActivityUserEntity> page = new Page<>(pageNum, pageSize);
        Page<BusinessActivityUserEntity> pageResult = page(page, new LambdaQueryWrapper<>());

        for (BusinessActivityUserEntity entity : pageResult.getRecords()) {
            BusinessActivityUserVO vo = new BusinessActivityUserVO();
            BeanUtils.copyProperties(entity, vo);
            ans.add(vo);
        }

        return ans;
    }

    @Override
    public Page<BusinessActivityUserVO> query(Integer pageNum, Integer pageSize, BusinessActivityUserDTO dto) {
        Page<BusinessActivityUserEntity> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<BusinessActivityUserEntity> queryWrapper = new LambdaQueryWrapper<>();
        // 自定义查询条件
        if (dto != null) {
            if (dto.getActivityId() != null) {
                queryWrapper.eq(BusinessActivityUserEntity::getActivityId, dto.getActivityId());
            }
            if (dto.getUserId() != null) {
                queryWrapper.eq(BusinessActivityUserEntity::getUserId, dto.getUserId());
            }
            if (dto.getUserRole() != null && !dto.getUserRole().isEmpty()) {
                queryWrapper.eq(BusinessActivityUserEntity::getUserRole, dto.getUserRole());
            }
        }

        // 默认按创建时间倒序
        queryWrapper.orderByDesc(BusinessActivityUserEntity::getCreateTime);

        Page<BusinessActivityUserEntity> entityPage = page(page, queryWrapper);

        // 转换为VO
        Page<BusinessActivityUserVO> voPage = new Page<>();
        BeanUtils.copyProperties(entityPage, voPage);

        List<BusinessActivityUserVO> voList = new ArrayList<>();
        for (BusinessActivityUserEntity entity : entityPage.getRecords()) {
            BusinessActivityUserVO vo = new BusinessActivityUserVO();
            BeanUtils.copyProperties(entity, vo);
            voList.add(vo);
        }

        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public BusinessActivityUserVO getDetailById(Long id) {
        // 参数校验
        if (id == null) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR.getMessage());
        }

        BusinessActivityUserEntity entity = getById(id);
        if (entity == null) {
            throw new BaseException(ErrorCode.BUSINESS_ACTIVITY_USER_NOT_EXISTS.getMessage());
        }

        BusinessActivityUserVO vo = new BusinessActivityUserVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}