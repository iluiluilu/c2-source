package com.aismartstore.backend.reporting;

import net.sf.jasperreports.charts.JRChart;
import net.sf.jasperreports.charts.JRChartCustomizer;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;

/**
 * Every chart in the report templates plots counts (documents, runs), so the value axis must tick on whole numbers only.
 * JFreeChart's default tick units would label a 0..1 range as 0.1, 0.2, ... Referenced by {@code customizerClass} in
 * {@code reports/chart_bar.jrxml} and {@code reports/chart_stacked.jrxml}.
 */
public class IntegerAxisChartCustomizer implements JRChartCustomizer {

    @Override
    public void customize(JFreeChart chart, JRChart jasperChart) {
        if (chart.getPlot() instanceof CategoryPlot plot && plot.getRangeAxis() instanceof NumberAxis axis) {
            axis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        }
    }
}
