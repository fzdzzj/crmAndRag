package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.ProjectFileCategory;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.ProjectFileVO;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.ProjectFileMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.ProjectFileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ProjectFileServiceImpl extends ServiceImpl<ProjectFileMapper, ProjectFileEntity> implements ProjectFileService {

    @Autowired
    private AttachmentDownloadTokenUtil downloadTokenUtil;

    @Autowired
    private HttpServletRequest request;

    @Autowired
    private BusinessActivityMapper businessActivityMapper;

    @Autowired
    private ContractOrderItemMapper contractOrderItemMapper;

    @Autowired
    private SalesOpportunityMapper salesOpportunityMapper;

    @Autowired
    private DataConvertService dataConvertService;

    @Autowired
    private AttachmentAccessService attachmentAccessService;

    @Autowired
    private UserMapper userMapper;

    @Value("${slz.file.path}")
    private String basePath;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void uploadByActivity(Long activityId, List<ProjectFileDTO> dtoList) {

        if (dtoList == null || dtoList.isEmpty()) {
            throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件列表不能为空");
        }

        validateCategory(dtoList);

        // 查询业务活动信息，获取关联的销售机会ID
        BusinessActivityEntity activity = businessActivityMapper.selectById(activityId);
        if (activity == null) {
            throw new BaseException(ErrorCode.DATA_NULL, "业务活动不存在");
        }

        Long currentUserId = BaseUnit.getCurrentId();
        
        // 记录已上传到磁盘的文件，用于失败时回滚
        List<File> uploadedFiles = new ArrayList<>();

        try {
            // 1. 先将所有文件写入磁盘
            List<ProjectFileEntity> entities = new ArrayList<>();
            for (ProjectFileDTO dto : dtoList) {
                ProjectFileEntity entity = writeFileToDisk(dto, activityId);
                entity.setCategory(dto.getCategory());
                entity.setTheme(dto.getTheme());
                entity.setDescription(dto.getDescription());
                entity.setUploaderId(currentUserId);
                entity.setUploadTime(LocalDateTime.now());
                entity.setActivityId(activityId);
                entity.setOpportunityId(activity.getOpportunityId());
                entities.add(entity);
                
                // 记录已上传的文件，用于失败回滚
                uploadedFiles.add(new File(entity.getFilePath(), entity.getFileName()));
            }

            // 2. 批量写入数据库
            for (ProjectFileEntity entity : entities) {
                baseMapper.insert(entity);
            }

            log.info("成功上传 {} 个文件到业务活动 {}", dtoList.size(), activityId);

        } catch (Exception e) {
            // 3. 失败时回滚：删除已上传的磁盘文件
            log.error("上传文件失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
            rollbackUploadedFiles(uploadedFiles);
            throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void uploadByOrder(Long orderId, List<ProjectFileDTO> dtoList) {

        if (dtoList == null || dtoList.isEmpty()) {
            throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件列表不能为空");
        }

        validateCategory(dtoList);

        // 查询订单项信息，获取关联合同ID
        ContractOrderItemEntity orderItem = contractOrderItemMapper.selectById(orderId);
        if (orderItem == null) {
            throw new BaseException(ErrorCode.DATA_NULL, "订单项不存在");
        }

        Long currentUserId = BaseUnit.getCurrentId();
        
        // 记录已上传到磁盘的文件，用于失败时回滚
        List<File> uploadedFiles = new ArrayList<>();

        try {
            // 1. 先将所有文件写入磁盘
            List<ProjectFileEntity> entities = new ArrayList<>();
            for (ProjectFileDTO dto : dtoList) {
                ProjectFileEntity entity = writeFileToDisk(dto, orderId);
                entity.setCategory(dto.getCategory());
                entity.setTheme(dto.getTheme());
                entity.setDescription(dto.getDescription());
                entity.setUploaderId(currentUserId);
                entity.setUploadTime(LocalDateTime.now());
                entity.setContractId(orderItem.getContractId());
                entity.setOrderId(orderId);
                entities.add(entity);
                
                // 记录已上传的文件，用于失败回滚
                uploadedFiles.add(new File(entity.getFilePath(), entity.getFileName()));
            }

            // 2. 批量写入数据库（在事务中）
            for (ProjectFileEntity entity : entities) {
                baseMapper.insert(entity);
            }

            log.info("成功上传 {} 个文件到订单项 {}", dtoList.size(), orderId);

        } catch (Exception e) {
            // 3. 失败时回滚：删除已上传的磁盘文件
            log.error("上传文件失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
            rollbackUploadedFiles(uploadedFiles);
            throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void uploadStandalone(List<ProjectFileDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件列表不能为空");
        }

        validateCategory(dtoList);

        Long currentUserId = BaseUnit.getCurrentId();
        
        // 记录已上传到磁盘的文件，用于失败时回滚
        List<File> uploadedFiles = new ArrayList<>();

        try {
            // 1. 先验证所有销售机会是否存在
            for (ProjectFileDTO dto : dtoList) {
                if (dto.getOpportunityId() != null) {
                    SalesOpportunityEntity opportunity = salesOpportunityMapper.selectById(dto.getOpportunityId());
                    if (opportunity == null) {
                        throw new BaseException(ErrorCode.DATA_NULL, "销售机会不存在，opportunityId=" + dto.getOpportunityId());
                    }
                }
            }

            // 2. 将所有文件写入磁盘
            List<ProjectFileEntity> entities = new ArrayList<>();
            for (ProjectFileDTO dto : dtoList) {
                ProjectFileEntity entity = writeFileToDisk(dto, null);
                entity.setCategory(dto.getCategory());
                entity.setTheme(dto.getTheme());
                entity.setDescription(dto.getDescription());
                entity.setUploaderId(currentUserId);
                entity.setUploadTime(LocalDateTime.now());
                entity.setOpportunityId(dto.getOpportunityId());
                entity.setContractId(dto.getContractId());
                entities.add(entity);
                
                // 记录已上传的文件，用于失败回滚
                uploadedFiles.add(new File(entity.getFilePath(), entity.getFileName()));
            }

            // 3. 批量写入数据库（在事务中）
            for (ProjectFileEntity entity : entities) {
                baseMapper.insert(entity);
            }

            log.info("成功上传 {} 个独立文件", dtoList.size());

        } catch (Exception e) {
            // 4. 失败时回滚：删除已上传的磁盘文件
            log.error("上传文件失败，回滚已上传的 {} 个文件", uploadedFiles.size(), e);
            rollbackUploadedFiles(uploadedFiles);
            throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件上传失败: " + e.getMessage());
        }
    }


    @Override
    public Page<ProjectFileVO> queryPage(Integer pageNum, Integer pageSize, ProjectFileQueryDTO queryDTO) {
        Page<ProjectFileEntity> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ProjectFileEntity> wrapper = buildQueryWrapper(queryDTO);
        wrapper.orderByDesc(ProjectFileEntity::getUploadTime);

        Page<ProjectFileEntity> entityPage = baseMapper.selectPage(page, wrapper);

        // 记录级数据范围：仅返回当前用户可读的文件；total 保留库内总数仅用于分页控件，越权行不返回
        List<ProjectFileEntity> readable = filterReadable(entityPage.getRecords());

        Page<ProjectFileVO> voPage = new Page<>(pageNum, pageSize, entityPage.getTotal());
        List<ProjectFileVO> voList = readable.stream()
                .map(this::entityToVO)
                .collect(Collectors.toList());
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public List<ProjectFileVO> listByActivityId(Long activityId) {
        List<ProjectFileEntity> entities = baseMapper.selectList(
                new LambdaQueryWrapper<ProjectFileEntity>()
                        .eq(ProjectFileEntity::getActivityId, activityId)
                        .orderByDesc(ProjectFileEntity::getUploadTime)
        );
        return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
    }

    @Override
    public List<ProjectFileVO> listByOrderId(Long orderId) {
        List<ProjectFileEntity> entities = baseMapper.selectList(
                new LambdaQueryWrapper<ProjectFileEntity>()
                        .eq(ProjectFileEntity::getOrderId, orderId)
                        .orderByDesc(ProjectFileEntity::getUploadTime)
        );
        return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
    }

    @Override
    public List<ProjectFileVO> listByContractId(Long contractId) {
        List<ProjectFileEntity> entities = baseMapper.selectList(
                new LambdaQueryWrapper<ProjectFileEntity>()
                        .eq(ProjectFileEntity::getContractId, contractId)
                        .orderByDesc(ProjectFileEntity::getUploadTime)
        );
        return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
    }

    @Override
    public List<ProjectFileVO> listByOpportunityId(Long opportunityId) {
        List<ProjectFileEntity> entities = baseMapper.selectList(
                new LambdaQueryWrapper<ProjectFileEntity>()
                        .eq(ProjectFileEntity::getOpportunityId, opportunityId)
                        .orderByDesc(ProjectFileEntity::getUploadTime)
        );
        return filterReadable(entities).stream().map(this::entityToVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        // 1. 先查询文件信息（用于后续删除磁盘文件）
        List<ProjectFileEntity> entities = baseMapper.selectBatchIds(ids);
        if (entities == null || entities.isEmpty()) {
            return;
        }

        // 主动删除分级：上传人本人可删自己的；超管可删全部；其他拒绝
        Long currentId = BaseUnit.getCurrentId();
        UserEntity currentUser = userMapper.selectById(currentId);
        boolean isAdmin = currentUser != null && java.util.Objects.equals(currentUser.getRoleId(), 1L);
        for (ProjectFileEntity entity : entities) {
            if (isAdmin || java.util.Objects.equals(entity.getUploaderId(), currentId)) {
                continue;
            }
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "仅上传人本人或超管可删除该项目文件");
        }

        // 2. 先删除数据库记录
        int deletedCount = baseMapper.deleteBatchIds(ids);
        if (deletedCount != ids.size()) {
            throw new BaseException(ErrorCode.FILE_READ_FAILED, 
                "删除失败: 期望删除" + ids.size() + "个，实际删除" + deletedCount + "个");
        }

        // 3. 再删除磁盘文件
        List<String> failedFiles = new ArrayList<>();
        for (ProjectFileEntity entity : entities) {
            try {
                deleteFileFromDisk(entity);
            } catch (Exception e) {
                log.error("删除磁盘文件失败: id={}, path={}", entity.getId(), entity.getFilePath(), e);
                failedFiles.add(entity.getFilePath() + entity.getFileName());
            }
        }

        // 4. 如果有删除失败的文件，记录警告日志
        if (!failedFiles.isEmpty()) {
            log.warn("以下文件磁盘删除失败，需要手动清理或等待定时任务处理: {}", failedFiles);
        }

        log.info("成功删除 {} 个文件", ids.size());
    }



    /**
     * 记录级数据范围过滤：仅保留当前用户可读的项目文件（统一走附件授权入口）。
     */
    private List<ProjectFileEntity> filterReadable(List<ProjectFileEntity> entities) {
        Long currentUserId = BaseUnit.getCurrentId();
        return entities.stream()
                .filter(e -> attachmentAccessService.canReadProjectFile(e, currentUserId))
                .collect(Collectors.toList());
    }

    /**
     * 校验文件分类是否合法
     */
    private void validateCategory(List<ProjectFileDTO> dtoList) {
        for (ProjectFileDTO dto : dtoList) {
            if (dto.getCategory() == null || !ProjectFileCategory.isValid(dto.getCategory())) {
                throw new BaseException(ErrorCode.PARAM_EMPTY, "文件分类不合法，必须为：VISIT_RECORD/MEETING_MINUTES/PROPOSAL/BID_DOCUMENT/PROJECT_CONTRACT");
            }
        }
    }

    /**
     * 将文件写入磁盘，设置文件基本属性
     */
    private ProjectFileEntity writeFileToDisk(ProjectFileDTO dto, Long relatedId) {
        MultipartFile fileData = dto.getFileData();
        if (fileData == null || fileData.isEmpty()) {
            throw new BaseException(ErrorCode.PARAM_EMPTY, "上传文件不能为空");
        }

        String originalFileName = fileData.getOriginalFilename();
        if (originalFileName == null || originalFileName.isEmpty()) {
            originalFileName = "unknown";
        }

        // 在文件名后添加时间戳
        String fileExtension = "";
        String fileNameWithoutExt = originalFileName;
        int lastDotIndex = originalFileName.lastIndexOf(".");
        if (lastDotIndex > 0) {
            fileExtension = originalFileName.substring(lastDotIndex);
            fileNameWithoutExt = originalFileName.substring(0, lastDotIndex);
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String storedFileName = fileNameWithoutExt + "-" + timestamp + fileExtension;

        // 构建存储路径: basePath/modelName/relatedId/
        String dirPath;
        if (relatedId != null) {
            dirPath = basePath + File.separator + ModelName.PROJECT_FILE + File.separator + relatedId;
        } else {
            dirPath = basePath + File.separator + ModelName.PROJECT_FILE + File.separator + "standalone";
        }

        // 必须使用绝对路径：transferTo 遇到相对路径会解析到 Tomcat 临时目录，导致目录不存在而写入失败
        File dir = new File(dirPath).getAbsoluteFile();
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            log.info("创建项目文件目录: dir={}, created={}", dir.getPath(), created);
        }

        File targetFile = new File(dir, storedFileName);
        try {
            fileData.transferTo(targetFile);
        } catch (IOException e) {
            log.error("项目文件写入失败: dir={}, fileName={}, dirExists={}, dirWritable={}",
                    dirPath, storedFileName, dir.exists(), dir.canWrite(), e);
            throw new BaseException(ErrorCode.FILE_READ_FAILED, "文件写入失败: " + e.getMessage());
        }

        ProjectFileEntity entity = new ProjectFileEntity();
        entity.setFileName(storedFileName);
        entity.setFilePath(dir.getPath() + File.separator);
        entity.setFileType(fileData.getContentType());
        entity.setFileSize(fileData.getSize());

        return entity;
    }

    /**
     * 回滚已上传的文件（删除磁盘文件）
     * @param files 已上传的文件列表
     */
    private void rollbackUploadedFiles(List<File> files) {
        for (File file : files) {
            try {
                if (file.exists() && !file.delete()) {
                    log.warn("回滚删除文件失败: {}", file.getAbsolutePath());
                }
            } catch (Exception e) {
                log.error("回滚删除文件异常: {}", file.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 从磁盘删除文件
     */
    private void deleteFileFromDisk(ProjectFileEntity entity) {
        if (entity.getFilePath() != null && entity.getFileName() != null) {
            File file = new File(entity.getFilePath(), entity.getFileName());
            if (file.exists()) {
                if(!file.delete()) {
                    throw new ServiceException(MessageConstant.FILE_DELETE_ERROR);
                }
            }
        }
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<ProjectFileEntity> buildQueryWrapper(ProjectFileQueryDTO queryDTO) {
        LambdaQueryWrapper<ProjectFileEntity> wrapper = new LambdaQueryWrapper<>();

        if (queryDTO == null) {
            return wrapper;
        }

        wrapper.eq(queryDTO.getCategory() != null, ProjectFileEntity::getCategory, queryDTO.getCategory())
                .like(queryDTO.getTheme() != null, ProjectFileEntity::getTheme, queryDTO.getTheme())
                .like(queryDTO.getDescription() != null, ProjectFileEntity::getDescription, queryDTO.getDescription())
                .eq(queryDTO.getUploaderId() != null, ProjectFileEntity::getUploaderId, queryDTO.getUploaderId())
                .eq(queryDTO.getActivityId() != null, ProjectFileEntity::getActivityId, queryDTO.getActivityId())
                .eq(queryDTO.getOpportunityId() != null, ProjectFileEntity::getOpportunityId, queryDTO.getOpportunityId())
                .eq(queryDTO.getContractId() != null, ProjectFileEntity::getContractId, queryDTO.getContractId())
                .eq(queryDTO.getOrderId() != null, ProjectFileEntity::getOrderId, queryDTO.getOrderId())
                .ge(queryDTO.getMinUploadTime() != null, ProjectFileEntity::getUploadTime, queryDTO.getMinUploadTime())
                .le(queryDTO.getMaxUploadTime() != null, ProjectFileEntity::getUploadTime, queryDTO.getMaxUploadTime());

        return wrapper;
    }

    /**
     * Entity转VO
     */
    private ProjectFileVO entityToVO(ProjectFileEntity entity) {
        ProjectFileVO vo = new ProjectFileVO();
        BeanUtils.copyProperties(entity, vo);

        // 查询上传人姓名
        if (entity.getUploaderId() != null) {
            vo.setUploaderName(dataConvertService.getUserName(entity.getUploaderId()));
        }

        // 生成下载URL：无权读取该文件的行不签发令牌（downloadUrl 置空）
        if (!attachmentAccessService.canReadProjectFile(entity, BaseUnit.getCurrentId())) {
            log.warn("拦截越权项目文件下载链接签发：fileId={}, requester={}", entity.getId(), BaseUnit.getCurrentId());
            return vo;
        }
        String baseUrl = getBaseUrl();
        Long currentUserId = BaseUnit.getCurrentId();
        String token = downloadTokenUtil.generateDownloadToken(entity.getId(), currentUserId, "project_file");
        vo.setDownloadUrl(baseUrl + "/public/attachment/download?token=" + token);

        return vo;
    }

    /**
     * 获取基础URL
     */
    private String getBaseUrl() {
        String contextPath = request.getContextPath();
        StringBuilder baseUrl = new StringBuilder();
        if (contextPath != null && !contextPath.isEmpty()) {
            baseUrl.append(contextPath);
        }
        return baseUrl.toString();
    }
}
