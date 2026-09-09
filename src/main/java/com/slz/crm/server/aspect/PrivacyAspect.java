package com.slz.crm.server.aspect;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.Excel;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.constant.PrivacyGetId;
import com.slz.crm.server.service.PermissionService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Aspect
@Component
@Slf4j
public class PrivacyAspect {

    @Autowired
    private PermissionService permissionService;

    @Pointcut( "execution(* com.slz.crm.server.service.impl.*.*(..)) && @annotation(com.slz.crm.server.annotation.Privacy)")
    public void shieldPrivacy(){}


    @Pointcut("execution(* com.slz.crm.common.untils.AOTOExcelUntil.AOTOExcelByStream(java.util.List, java.lang.String))")
    public void excelShieldPrivacy(){}



    @AfterReturning(
            pointcut = "shieldPrivacy()",
            returning = "result" // 与方法参数名一致，用于接收返回值
    )
    public void after(JoinPoint joinPoint,Object result){
        log.info("屏蔽隐私");

        List<Privacy> list = new ArrayList<>();

        //判断是否为List类型数据，并且将列表提取出来
        switch (result) {
            case List<?> a -> {
                for (Object o : a) {
                    if (o instanceof Privacy privacy) {
                       list.add(privacy);
                    }
                }
            }
            //判断是否为Page类型数据，并且将列表提取出来
            case Page<?> page -> {
                List<?> a = page.getRecords();
                for (Object o : a) {
                    if (o instanceof Privacy privacy) {
                        list.add(privacy);
                    }
                }
            }
            //判断是否为Privacy类型数据
            case Privacy privacy ->
                //进行隐私屏蔽
                    list.add(privacy);
            case null, default -> throw new ServiceException(MessageConstant.PRIVACY_ERR);
        }
        privacyShield(list);
    }

    @Before("excelShieldPrivacy()")
    public void before(JoinPoint joinPoint){
        log.info("切入Excel工具");

        //获取参数
        Object args = joinPoint.getArgs()[0];

        //判断是否为List
        if((args instanceof List<?> list)){

            List<Privacy> a = new ArrayList<>();

            list.forEach(item -> {
                if(item instanceof Privacy privacy){
                    a.add(privacy);
                }
            });

            privacyShield(a);


        }else {
            throw new ServiceException(MessageConstant.PRIVACY_ERR);
        }



    }

    /**
     * 隐私屏蔽
     */
    private void privacyShield(List<Privacy> list){

        RoleAO currentRole = BaseUnit.getCurrentRole();
        List<PermissionsEntity> permissionsEntityList = permissionService.getPermissionList(currentRole.getRoleId());

        list.forEach(o -> {
            //是否和自己有关，有关则不屏蔽
            if(isSelf(currentRole.getId(),o)){
                return;
            }
            //查看Phone权限
            if(!permissionService.hasPermission(PermissionOperates.PRIVACY_PHONE_VIEW,permissionsEntityList)){
                o.phone();
            }
            //查看Email权限
            if(!permissionService.hasPermission(PermissionOperates.PRIVACY_EMAIL_VIEW,permissionsEntityList)){
                o.email();
            }
            //TODO更多权限

        });
    }


    /**
     * 判断是否和自己有关，有关则不屏蔽
     * @param creatorId
     * @param privacy
     * @return
     */
    private boolean isSelf(Long creatorId,Privacy privacy){

        Class<? extends Privacy> aClass = privacy.getClass();

        Method[] methods = aClass.getDeclaredMethods();

        for (Method method : methods) {
            if(PrivacyGetId.USER_ID_RELATED_FIELDS.contains(method.getName())){
                try {
                    //调用方法获取用户ID
                    Long userId = (Long) method.invoke(privacy);
                    if(userId == null){
                        continue;
                    }
                    if(userId.equals(creatorId)){
                        return true;
                    }
                } catch (Exception e) {
                    log.error("隐私屏蔽错误", e);
                }
            }
        }

        // 额外关联用户（协助人/参与人等）：当前用户命中即视为本人，不脱敏
        Set<Long> relatedUserIds = privacy.relatedUserIds();
        if (relatedUserIds != null && relatedUserIds.contains(creatorId)) {
            return true;
        }

        return false;
    }


}
