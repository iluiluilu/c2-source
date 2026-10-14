package com.aismartstore.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.TickUnitSource;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.data.category.DefaultCategoryDataset;
import org.junit.jupiter.api.Test;

class IntegerAxisChartCustomizerTest {

    @Test
    void valueAxisOfACountChartOnlyTicksOnWholeNumbers() {
        DefaultCategoryDataset data = new DefaultCategoryDataset();
        data.addValue(1, "Đã xác nhận", "OCR");
        JFreeChart chart = ChartFactory.createStackedBarChart(null, null, null, data);

        new IntegerAxisChartCustomizer().customize(chart, null);

        NumberAxis axis = (NumberAxis) ((CategoryPlot) chart.getPlot()).getRangeAxis();
        TickUnitSource units = axis.getStandardTickUnits();
        // the smallest unit offered for a 0..1 range is 1, never 0.1
        assertThat(units.getCeilingTickUnit(0.1).getSize()).isEqualTo(1.0);
    }

    @Test
    void granularityHasAVietnameseLabelWhileTheWireValueStaysEnglish() {
        assertThat(Granularity.MONTH.label()).isEqualTo("Tháng");
        assertThat(Granularity.QUARTER.label()).isEqualTo("Quý");
        assertThat(Granularity.MONTH.wire()).isEqualTo("month");
    }
}
