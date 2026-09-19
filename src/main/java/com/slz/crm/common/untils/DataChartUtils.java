package com.slz.crm.common.untils;

import com.slz.crm.common.enumeration.ChartOperates.ChartOperate;
import com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate;
import java.awt.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.Map;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberTickUnitSource;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;

public class DataChartUtils {
  /**
   * 生成图表并返回字节数组
   *
   * @param chartType
   * @param title
   * @param chartData
   * @return
   */
  public static JFreeChart createChart(
      ChartOperate chartType, String title, Map<String, Number> chartData) {
    // 根据中文标题转换为对应的英文标题
    String englishTitle = convertToEnglishTitle(title);

    // 根据图表类型创建不同的图表
    switch (chartType) {
      case LINE_CHART:
        return createLineChart(chartData, englishTitle);
      case PIE_CHART:
        return createPieChart(chartData, englishTitle);
      default:
        throw new IllegalArgumentException("不支持的图表类型: " + chartType);
    }
  }

  /**
   * 将中文标题转换为英文标题
   *
   * @param chineseTitle 中文标题
   * @return 英文标题
   */
  public static String convertToEnglishTitle(String chineseTitle) {
    if ("签约合同数".equals(chineseTitle)) {
      return "Contract Count";
    } else if ("客户分布".equals(chineseTitle)) {
      return "Customer Distribution";
    } else if ("业绩".equals(chineseTitle)) {
      return "Performance";
    } else if ("回款金额".equals(chineseTitle)) {
      return "Payment Amount";
    } else if ("回款笔数".equals(chineseTitle)) {
      return "Payment Count";
    } else if ("回款状态分布".equals(chineseTitle)) {
      return "Payment Status Distribution";
    }
    return chineseTitle; // 如果没有匹配的，返回原标题
  }

  /** 创建折线图 */
  private static JFreeChart createLineChart(Map<String, Number> data, String title) {
    DefaultCategoryDataset dataset = new DefaultCategoryDataset();

    data.forEach(
        (category, value) -> {
          dataset.addValue(value, "Contract Count", category);
        });

    JFreeChart chart =
        ChartFactory.createLineChart(
            title, // 图表标题
            "Time", // 横轴标签
            "Value", // 纵轴标签
            dataset, // 数据集
            PlotOrientation.VERTICAL,
            true, // 是否显示图例
            true, // 是否生成工具提示
            false // 是否生成URL链接
            );

    // 自定义样式
    customizeChartAppearance(chart);
    return chart;
  }

  /** 创建饼图 */
  private static JFreeChart createPieChart(Map<String, Number> data, String title) {
    DefaultPieDataset dataset = new DefaultPieDataset();

    data.forEach(
        (category, value) -> {
          dataset.setValue(category, value);
        });

    JFreeChart chart =
        ChartFactory.createPieChart(
            title, // 图表标题
            dataset, // 数据集
            true, // 是否显示图例
            true, // 是否生成工具提示
            false // 是否生成URL链接
            );

    // 自定义样式
    customizeChartAppearance(chart);
    return chart;
  }

  /** 自定义图表外观 */
  private static void customizeChartAppearance(JFreeChart chart) {
    // 设置背景色
    chart.setBackgroundPaint(Color.white);

    // 设置标题样式
    TextTitle title = chart.getTitle();
    title.setFont(new Font("Arial", Font.BOLD, 16));

    // 设置图例样式
    if (chart.getLegend() != null) {
      chart.getLegend().setItemFont(new Font("Arial", Font.PLAIN, 12));
    }

    // 设置绘图区样式
    Plot plot = chart.getPlot();
    plot.setBackgroundPaint(Color.white);
    plot.setOutlinePaint(Color.gray);

    if (plot instanceof CategoryPlot) {
      CategoryPlot categoryPlot = (CategoryPlot) plot;
      // 设置横轴样式
      CategoryAxis domainAxis = categoryPlot.getDomainAxis();
      domainAxis.setTickLabelFont(new Font("Arial", Font.PLAIN, 12));
      domainAxis.setLabelFont(new Font("Arial", Font.BOLD, 13));

      // 设置纵轴样式
      ValueAxis rangeAxis = categoryPlot.getRangeAxis();
      rangeAxis.setTickLabelFont(new Font("Arial", Font.PLAIN, 12));
      rangeAxis.setLabelFont(new Font("Arial", Font.BOLD, 13));
      rangeAxis.setStandardTickUnits(new NumberTickUnitSource(true));
    } else if (plot instanceof PiePlot) {
      PiePlot piePlot = (PiePlot) plot;
      // 设置饼图区块标签字体
      piePlot.setLabelFont(new Font("Arial", Font.PLAIN, 12));
      // 设置饼图区块标签轮廓
      piePlot.setLabelOutlinePaint(Color.gray);

      // 设置饼图区块标签阴影
      piePlot.setLabelShadowPaint(Color.lightGray);

      // 设置饼图区块标签颜色
      piePlot.setLabelPaint(Color.black);

      // 设置标签生成器（可选，自定义标签格式）
      piePlot.setLabelGenerator(
          new StandardPieSectionLabelGenerator(
              "{0}: {1} ({2})", // 格式：名称: 数值 (百分比)
              new DecimalFormat("0"), // 数值格式
              new DecimalFormat("0.00%") // 百分比格式
              ));
    }
  }

  /** 计算时间范围 */
  public static LocalDateTime[] calculateDateRange(
      LocalDateTime endDate, LocalDateTime startDate, TimeRangeOperate timeRange) {
    if (endDate == null) {
      endDate = LocalDateTime.now();
    }
    switch (timeRange) {
      case THIS_WEEK:
        startDate = endDate.minusWeeks(1);
        break;
      case THIS_MONTH:
        startDate = endDate.minusMonths(1);
        break;
      case THIS_YEAR:
        int currentYear = endDate.getYear();
        startDate = LocalDateTime.of(currentYear, 1, 1, 0, 0, 0);
        endDate = LocalDateTime.of(currentYear, 12, 31, 23, 59, 59);
        break;
      default:
        startDate = endDate.minusMonths(1); // 默认一个月
    }

    return new LocalDateTime[] {startDate, endDate};
  }
}
