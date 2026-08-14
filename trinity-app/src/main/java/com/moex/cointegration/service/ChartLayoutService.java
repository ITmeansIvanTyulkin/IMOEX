package com.moex.cointegration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moex.cointegration.config.ImoexProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Persists operator chart layouts (desk + terminal) under data/chart-layouts/{user}.json.
 */
@Service
public class ChartLayoutService {

    private static final Logger log = LoggerFactory.getLogger(ChartLayoutService.class);
    private static final Pattern SAFE = Pattern.compile("[^a-zA-Z0-9._@+-]+");

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public ChartLayoutService(ImoexProperties imoexProperties) {
        this.root = Path.of(imoexProperties.dataDir(), "chart-layouts");
    }

    public ObjectNode load(String userKey) {
        Path file = fileFor(userKey);
        if (!Files.isRegularFile(file)) {
            return emptyDoc(userKey);
        }
        try {
            JsonNode n = mapper.readTree(file.toFile());
            if (n instanceof ObjectNode on) {
                return on;
            }
        } catch (Exception ex) {
            log.warn("Could not load chart layout {}: {}", file, ex.getMessage());
        }
        return emptyDoc(userKey);
    }

    public ObjectNode save(String userKey, JsonNode body) {
        ObjectNode doc = body instanceof ObjectNode on ? on.deepCopy() : emptyDoc(userKey);
        doc.put("user", sanitize(userKey));
        doc.put("updatedAt", Instant.now().toString());
        Path file = fileFor(userKey);
        try {
            Files.createDirectories(root);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), doc);
        } catch (Exception ex) {
            log.warn("Could not save chart layout {}: {}", file, ex.getMessage());
        }
        return doc;
    }

    private ObjectNode emptyDoc(String userKey) {
        ObjectNode n = mapper.createObjectNode();
        n.put("user", sanitize(userKey));
        n.put("updatedAt", Instant.now().toString());
        n.set("desk", mapper.createObjectNode());
        n.set("terminal", mapper.createObjectNode());
        return n;
    }

    private Path fileFor(String userKey) {
        return root.resolve(sanitize(userKey) + ".json");
    }

    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "anonymous";
        }
        String s = SAFE.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
        if (s.length() > 80) {
            s = s.substring(0, 80);
        }
        return s.isBlank() ? "anonymous" : s;
    }
}
