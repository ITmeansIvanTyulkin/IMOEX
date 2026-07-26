package com.moex.cointegration.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import com.moex.cointegration.model.AnalysisReport;
import com.moex.cointegration.model.Candle;
import com.moex.cointegration.model.PairAnalysisResult;
import com.moex.cointegration.model.WalkForwardReport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Локальное файловое хранилище свечей, отчётов анализа и путей к графикам.
 */
@Component
public class MarketDataStorage {

    private final Path dataDir;
    private final Path candlesDir;
    private final Path reportFile;
    private final Path walkForwardFile;
    private final ObjectMapper objectMapper;

    /**
     * Создаёт каталоги {@code data/} и {@code data/charts/}, если их ещё нет.
     *
     * @param properties настройки путей из конфигурации
     */
    public MarketDataStorage(ImoexProperties properties) throws IOException {
        this.dataDir = Path.of(properties.dataDir());
        this.candlesDir = dataDir.resolve("candles");
        this.reportFile = dataDir.resolve("analysis-report.json");
        this.walkForwardFile = dataDir.resolve("walk-forward-report.json");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Files.createDirectories(candlesDir);
        Files.createDirectories(Path.of(properties.chartsDir()));
    }

    /**
     * Сохраняет свечи тикера в JSON-файл {@code data/candles/{TICKER}.json}.
     */
    public void saveCandles(String ticker, List<Candle> candles) throws IOException {
        Path file = candlesDir.resolve(ticker + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), candles);
    }

    /**
     * Читает ранее сохранённые свечи; возвращает пустой список, если файла нет.
     */
    public List<Candle> loadCandles(String ticker) throws IOException {
        Path file = candlesDir.resolve(ticker + ".json");
        if (!Files.exists(file)) {
            return List.of();
        }
        Candle[] candles = objectMapper.readValue(file.toFile(), Candle[].class);
        return new ArrayList<>(List.of(candles));
    }

    /**
     * Возвращает список тикеров, для которых есть локальные JSON-файлы свечей.
     */
    public List<String> listStoredTickers() throws IOException {
        if (!Files.exists(candlesDir)) {
            return List.of();
        }
        try (var stream = Files.list(candlesDir)) {
            return stream
                    .filter(path -> path.toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .toList();
        }
    }

    /** Сохраняет последний отчёт анализа в {@code data/analysis-report.json}. */
    public void saveReport(AnalysisReport report) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
    }

    /** Загружает последний сохранённый отчёт анализа, если он существует. */
    public Optional<AnalysisReport> loadReport() throws IOException {
        if (!Files.exists(reportFile)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(reportFile.toFile(), AnalysisReport.class));
    }

    public void saveWalkForwardReport(WalkForwardReport report) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(walkForwardFile.toFile(), report);
    }

    public Optional<WalkForwardReport> loadWalkForwardReport() throws IOException {
        if (!Files.exists(walkForwardFile)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(walkForwardFile.toFile(), WalkForwardReport.class));
    }

    /**
     * Последняя цена close по тикеру или empty.
     */
    public Optional<Double> lastClose(String ticker) {
        try {
            List<Candle> candles = loadCandles(ticker);
            if (candles.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(candles.get(candles.size() - 1).close());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public Path candlesHourlyDir() throws IOException {
        Path dir = dataDir.resolve("candles-1h");
        Files.createDirectories(dir);
        return dir;
    }

    public void saveHourlyCandles(String ticker, List<Candle> candles) throws IOException {
        Path file = candlesHourlyDir().resolve(ticker + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), candles);
    }

    public List<Candle> loadHourlyCandles(String ticker) throws IOException {
        Path file = candlesHourlyDir().resolve(ticker + ".json");
        if (!Files.exists(file)) {
            return List.of();
        }
        Candle[] candles = objectMapper.readValue(file.toFile(), Candle[].class);
        return new ArrayList<>(List.of(candles));
    }

    /**
     * Ищет пару в топ-N последнего отчёта по тикерам Y и X.
     *
     * @return результат анализа пары или пусто, если отчёта/пары нет
     */
    public Optional<PairAnalysisResult> findPair(String tickerY, String tickerX) throws IOException {
        Optional<AnalysisReport> report = loadReport();
        if (report.isEmpty()) {
            return Optional.empty();
        }
        return report.get().topPairs().stream()
                .filter(pair -> pair.tickerY().equalsIgnoreCase(tickerY)
                        && pair.tickerX().equalsIgnoreCase(tickerX))
                .findFirst();
    }
}
