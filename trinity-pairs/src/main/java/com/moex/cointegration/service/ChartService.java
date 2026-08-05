package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.SpreadPoint;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * Генерация PNG-графиков спреда и Z-score (запасной вариант; основной UI — HTML-графики).
 */
@Service
public class ChartService {

    private final PairLookupService pairLookupService;
    private final ImoexProperties properties;

    public ChartService(PairLookupService pairLookupService, ImoexProperties properties) {
        this.pairLookupService = pairLookupService;
        this.properties = properties;
    }

    public Path renderSpreadChart(String tickerY, String tickerX) throws IOException {
        PairAnalysisResult pair = pairLookupService.requirePair(tickerY, tickerX);

        Path spreadPath = chartPath(tickerY, tickerX, "spread");
        Path zScorePath = chartPath(tickerY, tickerX, "zscore");

        writeTimeSeriesChart(
                spreadPath,
                "Spread: " + tickerY + " ~ " + tickerX,
                "Spread (log prices)",
                pair.spreadSeries(),
                Color.BLUE,
                false
        );

        writeZScoreChart(zScorePath, tickerY, tickerX, pair.zScoreSeries());
        return spreadPath;
    }

    public Path renderZScoreChart(String tickerY, String tickerX) throws IOException {
        PairAnalysisResult pair = pairLookupService.requirePair(tickerY, tickerX);
        Path zScorePath = chartPath(tickerY, tickerX, "zscore");
        writeZScoreChart(zScorePath, tickerY, tickerX, pair.zScoreSeries());
        return zScorePath;
    }

    public Path chartPath(String tickerY, String tickerX, String type) {
        String fileName = tickerY + "_" + tickerX + "_" + type + ".png";
        return Path.of(properties.chartsDir()).resolve(fileName);
    }

    private void writeZScoreChart(Path output, String y, String x, List<SpreadPoint> points) throws IOException {
        writeTimeSeriesChart(
                output,
                "Z-Score: " + y + " ~ " + x,
                "Z-Score",
                points,
                new Color(220, 38, 38),
                true
        );
    }

    private void writeTimeSeriesChart(
            Path output,
            String title,
            String yLabel,
            List<SpreadPoint> points,
            Color color,
            boolean zScoreLevels
    ) throws IOException {
        Files.createDirectories(output.getParent());

        TimeSeries series = new TimeSeries(yLabel);
        for (SpreadPoint point : points) {
            Date date = Date.from(point.date().atStartOfDay(ZoneId.systemDefault()).toInstant());
            series.addOrUpdate(new Day(date), point.value());
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection(series);
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title, "Date", yLabel, dataset, false, true, false);

        XYPlot plot = chart.getXYPlot();
        plot.getRenderer().setSeriesPaint(0, color);
        plot.getRenderer().setSeriesStroke(0, new BasicStroke(1.5f));

        if (zScoreLevels && !points.isEmpty()) {
            double zEntry = properties.cointegration().zScoreEntry();
            plot.addRangeMarker(new ValueMarker(0.0, Color.GRAY, new BasicStroke(1.0f)));
            plot.addRangeMarker(dashedMarker(zEntry, new Color(220, 38, 38)));
            plot.addRangeMarker(dashedMarker(-zEntry, new Color(22, 163, 74)));

            SpreadPoint last = points.get(points.size() - 1);
            double lastX = Date.from(last.date().atStartOfDay(ZoneId.systemDefault()).toInstant()).getTime();
            if (last.value() <= -zEntry) {
                XYTextAnnotation buy = new XYTextAnnotation("▲ КУПИТЬ", lastX, last.value());
                buy.setFont(new Font("SansSerif", Font.BOLD, 12));
                buy.setPaint(new Color(22, 163, 74));
                buy.setTextAnchor(TextAnchor.TOP_CENTER);
                plot.addAnnotation(buy);
            } else if (last.value() >= zEntry) {
                XYTextAnnotation sell = new XYTextAnnotation("▼ ПРОДАТЬ", lastX, last.value());
                sell.setFont(new Font("SansSerif", Font.BOLD, 12));
                sell.setPaint(new Color(220, 38, 38));
                sell.setTextAnchor(TextAnchor.BOTTOM_CENTER);
                plot.addAnnotation(sell);
            }
        }

        ChartUtils.saveChartAsPNG(output.toFile(), chart, 1200, 600);
    }

    private ValueMarker dashedMarker(double value, Color color) {
        ValueMarker marker = new ValueMarker(value, color, new BasicStroke(
                1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{6f, 4f}, 0));
        marker.setLabel(String.format("%.1f", value));
        return marker;
    }
}
