package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Runtime trend playbook settings (signal-only vs auto-execution), persisted under data/.
 */
@Service
@ConditionalOnProperty(prefix = "imoex.strategies.trend", name = "enabled", havingValue = "true")
public class TrendSettingsService {

    private static final Logger log = LoggerFactory.getLogger(TrendSettingsService.class);

    private final Path settingsFile;
    private final boolean ymlAutoExecution;
    private final boolean ymlLiveExecution;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private volatile Stored stored;

    public TrendSettingsService(
            ImoexProperties imoexProperties,
            @Value("${imoex.strategies.trend.auto-execution:false}") boolean ymlAutoExecution,
            @Value("${imoex.strategies.trend.live-execution:false}") boolean ymlLiveExecution
    ) {
        this.settingsFile = Path.of(imoexProperties.dataDir(), "trend-ui-settings.json");
        this.ymlAutoExecution = ymlAutoExecution;
        this.ymlLiveExecution = ymlLiveExecution;
        this.stored = new Stored(ymlAutoExecution, ymlLiveExecution, LocalDateTime.now());
    }

    @PostConstruct
    void load() {
        if (!Files.isRegularFile(settingsFile)) {
            return;
        }
        try {
            Stored loaded = mapper.readValue(settingsFile.toFile(), Stored.class);
            if (loaded != null) {
                stored = loaded;
            }
        } catch (Exception ex) {
            log.warn("Could not load trend UI settings {}: {}", settingsFile, ex.getMessage());
        }
    }

    public synchronized View view() {
        Stored s = stored;
        return new View(
                s.autoExecution(),
                s.liveExecution(),
                s.autoExecution() ? "AUTO" : "SIGNAL_ONLY",
                s.updatedAt()
        );
    }

    public boolean autoExecution() {
        return stored.autoExecution();
    }

    public boolean liveExecution() {
        return stored.liveExecution();
    }

    public synchronized View setAutoExecution(boolean autoExecution) {
        Stored next = new Stored(autoExecution, stored.liveExecution(), LocalDateTime.now());
        stored = next;
        save(next);
        log.info("Trend delivery mode → {}", autoExecution ? "AUTO" : "SIGNAL_ONLY");
        return view();
    }

    public synchronized View save(UpdateRequest request) {
        if (request == null) {
            return view();
        }
        boolean auto = request.autoExecution() != null ? request.autoExecution() : stored.autoExecution();
        boolean live = request.liveExecution() != null ? request.liveExecution() : stored.liveExecution();
        Stored next = new Stored(auto, live, LocalDateTime.now());
        stored = next;
        save(next);
        return view();
    }

    private void save(Stored next) {
        try {
            Files.createDirectories(settingsFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), next);
        } catch (Exception ex) {
            log.warn("Could not save trend UI settings {}: {}", settingsFile, ex.getMessage());
        }
    }

    public record Stored(boolean autoExecution, boolean liveExecution, LocalDateTime updatedAt) {
    }

    public record View(boolean autoExecution, boolean liveExecution, String delivery, LocalDateTime updatedAt) {
    }

    public record UpdateRequest(Boolean autoExecution, Boolean liveExecution) {
    }
}
