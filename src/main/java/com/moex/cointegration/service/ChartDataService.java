package com.moex.cointegration.service;

import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.ChartPayload;
import com.moex.cointegration.model.ChartPayload.CandleBar;
import com.moex.cointegration.model.ChartPayload.ChartMarker;
import com.moex.cointegration.model.ChartPayload.DateValue;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.SpreadPoint;
import com.moex.cointegration.model.TradingRecommendation;
import com.moex.cointegration.model.TradingSignal;
import com.moex.cointegration.quant.KaufmanAdaptiveMa;
import com.moex.cointegration.quant.SignalRules;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Собирает данные для интерактивных графиков: свечи, дивергенция, KAMA, Z-score, стрелки входа/выхода.
 */
@Service
public class ChartDataService {

    private final PairLookupService pairLookupService;
    private final TradingRecommendationService recommendationService;
    private final ImoexProperties properties;

    public ChartDataService(
            PairLookupService pairLookupService,
            TradingRecommendationService recommendationService,
            ImoexProperties properties
    ) {
        this.pairLookupService = pairLookupService;
        this.recommendationService = recommendationService;
        this.properties = properties;
    }

    public ChartPayload build(String tickerY, String tickerX) throws IOException {
        PairAnalysisResult pair = pairLookupService.requirePair(tickerY, tickerX);
        PairLookupService.AlignedCandles aligned = pairLookupService.loadAlignedCandles(tickerY, tickerX);

        Map<LocalDate, Double> spreadByDate = toMap(pair.spreadSeries());
        Map<LocalDate, Double> zByDate = toMap(pair.zScoreSeries());

        List<LocalDate> dates = pair.spreadSeries().stream().map(SpreadPoint::date).toList();
        double[] spreadArr = pair.spreadSeries().stream().mapToDouble(SpreadPoint::value).toArray();
        double[] kamaArr = KaufmanAdaptiveMa.computeDefault(spreadArr);

        double baseY = aligned.candlesY().isEmpty() ? 1.0 : aligned.candlesY().get(0).close();
        double baseX = aligned.candlesX().isEmpty() ? 1.0 : aligned.candlesX().get(0).close();

        List<CandleBar> candlesY = new ArrayList<>();
        List<CandleBar> candlesX = new ArrayList<>();
        List<DateValue> normY = new ArrayList<>();
        List<DateValue> normX = new ArrayList<>();
        List<DateValue> spread = new ArrayList<>();
        List<DateValue> kama = new ArrayList<>();
        List<DateValue> zScore = new ArrayList<>();

        Map<LocalDate, Candle> yMap = indexCandles(aligned.candlesY());
        Map<LocalDate, Candle> xMap = indexCandles(aligned.candlesX());

        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            String time = d.toString();

            Candle cy = yMap.get(d);
            Candle cx = xMap.get(d);
            if (cy != null) {
                candlesY.add(toBar(cy));
                normY.add(new DateValue(time, cy.close() / baseY * 100.0));
            }
            if (cx != null) {
                candlesX.add(toBar(cx));
                normX.add(new DateValue(time, cx.close() / baseX * 100.0));
            }

            Double s = spreadByDate.get(d);
            Double z = zByDate.get(d);
            if (s != null) {
                spread.add(new DateValue(time, s));
            }
            if (!Double.isNaN(kamaArr[i])) {
                kama.add(new DateValue(time, kamaArr[i]));
            }
            if (z != null) {
                zScore.add(new DateValue(time, z));
            }
        }

        double zEntry = properties.cointegration().zScoreEntry();
        double zExit = properties.cointegration().zScoreExit();
        boolean reversal = properties.cointegration().entryReversalRequired();
        List<ChartMarker> markers = buildMarkers(pair.zScoreSeries(), zEntry, zExit, reversal);

        TradingRecommendation rec = recommendationService.findForPair(tickerY, tickerX)
                .orElse(null);

        return new ChartPayload(
                tickerY,
                tickerX,
                rec != null ? rec.signal().name() : guessSignal(pair, zEntry, reversal).name(),
                lastZ(pair),
                pair.hedgeRatio(),
                pair.halfLifeDays(),
                pair.sharpeRatio(),
                zEntry,
                zExit,
                rec != null ? rec.summary() : "См. график Z-score и уровни входа",
                rec != null ? rec.details() : "",
                candlesY,
                candlesX,
                normY,
                normX,
                spread,
                kama,
                zScore,
                markers
        );
    }

    private List<ChartMarker> buildMarkers(
            List<SpreadPoint> zSeries,
            double zEntry,
            double zExit,
            boolean requireReversal
    ) {
        List<ChartMarker> markers = new ArrayList<>();
        if (zSeries.size() < 2) {
            return markers;
        }

        int position = 0; // +1 long spread, -1 short spread, 0 flat
        for (int i = 1; i < zSeries.size(); i++) {
            double prev = zSeries.get(i - 1).value();
            double cur = zSeries.get(i).value();
            String time = zSeries.get(i).date().toString();

            if (position == 0) {
                if (SignalRules.confirmLongEntry(prev, cur, zEntry, requireReversal)) {
                    position = 1;
                    markers.add(new ChartMarker(time, "belowBar", "#16a34a", "arrowUp", "КУПИТЬ спред", "zscore"));
                } else if (SignalRules.confirmShortEntry(prev, cur, zEntry, requireReversal)) {
                    position = -1;
                    markers.add(new ChartMarker(time, "aboveBar", "#dc2626", "arrowDown", "ПРОДАТЬ спред", "zscore"));
                }
            } else if (position == 1 && SignalRules.exitLong(prev, cur, zExit)) {
                position = 0;
                markers.add(new ChartMarker(time, "aboveBar", "#64748b", "circle", "ВЫХОД", "zscore"));
            } else if (position == -1 && SignalRules.exitShort(prev, cur, zExit)) {
                position = 0;
                markers.add(new ChartMarker(time, "belowBar", "#64748b", "circle", "ВЫХОД", "zscore"));
            }
        }

        // «СЕЙЧАС» только если на последнем баре реально сработал вход (разворот),
        // либо мягкая метка «в зоне / ждём разворот» без второй стрелки входа.
        int last = zSeries.size() - 1;
        double zPrev = zSeries.get(last - 1).value();
        double zCur = zSeries.get(last).value();
        String lastTime = zSeries.get(last).date().toString();

        if (SignalRules.confirmLongEntry(zPrev, zCur, zEntry, requireReversal) && position != 1) {
            // already marked in loop when position was 0; if last bar is entry, loop added it
        } else if (SignalRules.confirmShortEntry(zPrev, zCur, zEntry, requireReversal) && position != -1) {
            // same
        }

        boolean lastIsEntryLong = SignalRules.confirmLongEntry(zPrev, zCur, zEntry, requireReversal);
        boolean lastIsEntryShort = SignalRules.confirmShortEntry(zPrev, zCur, zEntry, requireReversal);

        if (lastIsEntryLong) {
            markers.add(new ChartMarker(lastTime, "belowBar", "#16a34a", "arrowUp", "СЕЙЧАС: КУПИТЬ", "zscore"));
        } else if (lastIsEntryShort) {
            markers.add(new ChartMarker(lastTime, "aboveBar", "#dc2626", "arrowDown", "СЕЙЧАС: ПРОДАТЬ", "zscore"));
        } else if (position == -1) {
            markers.add(new ChartMarker(lastTime, "aboveBar", "#f97316", "circle", "В ШОРТЕ (удержание)", "zscore"));
        } else if (position == 1) {
            markers.add(new ChartMarker(lastTime, "belowBar", "#22c55e", "circle", "В ЛОНГЕ (удержание)", "zscore"));
        } else if (!Double.isNaN(zCur) && zCur >= zEntry) {
            markers.add(new ChartMarker(lastTime, "aboveBar", "#a8a29e", "circle", "ЖДЁМ РАЗВОРОТ ↓", "zscore"));
        } else if (!Double.isNaN(zCur) && zCur <= -zEntry) {
            markers.add(new ChartMarker(lastTime, "belowBar", "#a8a29e", "circle", "ЖДЁМ РАЗВОРОТ ↑", "zscore"));
        }

        return markers;
    }

    private TradingSignal guessSignal(PairAnalysisResult pair, double zEntry, boolean reversal) {
        List<SpreadPoint> zSeries = pair.zScoreSeries();
        if (zSeries.size() < 2) {
            return TradingSignal.HOLD;
        }
        double prev = zSeries.get(zSeries.size() - 2).value();
        double cur = zSeries.get(zSeries.size() - 1).value();
        if (SignalRules.confirmLongEntry(prev, cur, zEntry, reversal)) {
            return TradingSignal.LONG_SPREAD;
        }
        if (SignalRules.confirmShortEntry(prev, cur, zEntry, reversal)) {
            return TradingSignal.SHORT_SPREAD;
        }
        if (!Double.isNaN(cur) && Math.abs(cur) >= zEntry) {
            return TradingSignal.WATCH;
        }
        return TradingSignal.HOLD;
    }

    private double lastZ(PairAnalysisResult pair) {
        List<SpreadPoint> z = pair.zScoreSeries();
        return z.get(z.size() - 1).value();
    }

    private Map<LocalDate, Double> toMap(List<SpreadPoint> points) {
        Map<LocalDate, Double> map = new HashMap<>();
        for (SpreadPoint p : points) {
            map.put(p.date(), p.value());
        }
        return map;
    }

    private Map<LocalDate, Candle> indexCandles(List<Candle> candles) {
        Map<LocalDate, Candle> map = new HashMap<>();
        for (Candle c : candles) {
            map.put(c.date(), c);
        }
        return map;
    }

    private CandleBar toBar(Candle c) {
        return new CandleBar(c.date().toString(), c.open(), c.high(), c.low(), c.close());
    }
}
