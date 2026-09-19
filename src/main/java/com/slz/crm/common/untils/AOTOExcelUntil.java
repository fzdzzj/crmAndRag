package com.slz.crm.common.untils;

import com.alibaba.excel.EasyExcel;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.pojo.ao.Excel;
import com.slz.crm.pojo.excel.*;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AOTOExcelUntil {

  private final List<Class<?>> excelClass = new ArrayList<>();
  ;

  @PostConstruct
  public void load() {
    excelClass.add(CustomerCompanyExcel.class);
    excelClass.add(UserExcel.class);
    excelClass.add(CustomerContactExcel.class);
    excelClass.add(GetCustomerCompanyExcel.class);
    excelClass.add(GetCustomerContactExcel.class);

    log.info("AOTOExcelUntil 加载的excel类有：{}", excelClass);
  }

  /**
   * 导出excel
   *
   * @param excels 导出的excel对象
   * @param sheetName sheet名称
   * @return 字节码文件
   */
  public byte[] AOTOExcelByStream(List<? extends Excel> excels, String sheetName) {
    Class<?> clazz = null;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    if (excels == null || excels.isEmpty()) {
      throw new ServiceException("禁止传入空集合！！！");
    }

    // 获取传入的Excel类
    for (Class<?> aClass : excelClass) {
      if (aClass.isInstance(excels.getFirst())) {
        clazz = aClass;
        break;
      }
    }

    if (clazz == null) {
      throw new ServiceException("禁止传入  非继承Excel接口并且不处于com.slz.crm.pojo.excel包下   的类！！！");
    }

    // 这里需要注意 同一个sheet只能写一次
    EasyExcel.write(outputStream, clazz).sheet(sheetName).doWrite(excels);

    return outputStream.toByteArray();
  }
}
