package com.slz.crm;

import com.slz.crm.server.init.DataInitializer;
import com.slz.crm.server.init.PermissionSyncRunner;
import com.tangzc.autotable.springboot.EnableAutoTable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAutoTable  // 暂时禁用自动建表
@EnableScheduling
@SpringBootApplication
@MapperScan("com.slz.crm.server.mapper")
@EntityScan("com.slz.crm.pojo.entity")
public class CrmApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(CrmApplication.class, args);

        //同步数据库权限
        context.getBean(PermissionSyncRunner.class).start();

        //初始化管理员角色、账号与权限绑定
        context.getBean(DataInitializer.class).start();
    }

}
