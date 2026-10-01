package com.moex.cointegration.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the last Supabase user session for cloud desk snapshot upserts.
 * Survives JVM restart via {@code data/desk-cloud-session.json}.
 */
@Component
public class DeskCloudSessionStore {

    private static final Logger log = LoggerFactory.getLogger(DeskCloudSessionStore.class);
    private static final String FILE_NAME = "desk-cloud-session.json";

    private final Path file;
    private final ObjectMapper mapper;
    private final AtomicReference<Session> session = new AtomicReference<>();

    public DeskCloudSessionStore(ImoexProperties properties, ObjectMapper objectMapper) {
        String dir = properties.dataDir() == null || properties.dataDir().isBlank()
                ? "data"
                : properties.dataDir();
        this.file = Path.of(dir, FILE_NAME);
        this.mapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    public synchronized void remember(
            String email,
            String accessToken,
            String refreshToken,
            long expiresInSeconds
    ) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        Session prev = session.get();
        String userId = userIdFromJwt(accessToken);
        long exp = Instant.now().getEpochSecond()
                + Math.max(60L, expiresInSeconds > 0 ? expiresInSeconds : 3600L);
        String emailOut = email == null ? "" : email.trim();
        if (emailOut.isBlank() && prev != null && prev.email() != null) {
            emailOut = prev.email();
        }
        String refreshOut = refreshToken == null ? "" : refreshToken.trim();
        if (refreshOut.isBlank() && prev != null && prev.refreshToken() != null) {
            refreshOut = prev.refreshToken();
        }
        Session next = new Session(
                emailOut,
                userId,
                accessToken.trim(),
                refreshOut,
                exp,
                Instant.now().toString()
        );
        session.set(next);
        save(next);
        log.info("Desk cloud session stored for {} (user_id={})",
                next.email().isBlank() ? "?" : next.email(),
                next.userId().isBlank() ? "?" : next.userId());
    }

    public synchronized void clear() {
        session.set(null);
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("Could not delete {}: {}", file, e.toString());
        }
    }

    public Session current() {
        return session.get();
    }

    public boolean hasSession() {
        Session s = session.get();
        return s != null && s.accessToken() != null && !s.accessToken().isBlank();
    }

    public synchronized void replaceTokens(String accessToken, String refreshToken, long expiresInSeconds) {
        Session prev = session.get();
        if (prev == null) {
            remember("", accessToken, refreshToken, expiresInSeconds);
            return;
        }
        remember(prev.email(), accessToken, refreshToken, expiresInSeconds);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Session loaded = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Session.class);
            if (loaded != null && loaded.accessToken() != null && !loaded.accessToken().isBlank()) {
                if (loaded.userId() == null || loaded.userId().isBlank()) {
                    loaded = new Session(
                            loaded.email(),
                            userIdFromJwt(loaded.accessToken()),
                            loaded.accessToken(),
                            loaded.refreshToken(),
                            loaded.expiresAtEpochSec(),
                            loaded.savedAt()
                    );
                }
                session.set(loaded);
                log.info("Restored desk cloud session for {}",
                        loaded.email() == null || loaded.email().isBlank() ? "?" : loaded.email());
            }
        } catch (Exception e) {
            log.warn("Could not load {}: {}", file, e.toString());
        }
    }

    private void save(Session s) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not save {}: {}", file, e.toString());
        }
    }

    static String userIdFromJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return "";
            }
            String json = new String(
                    Base64.getUrlDecoder().decode(pad(parts[1])),
                    StandardCharsets.UTF_8
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = new ObjectMapper().readValue(json, Map.class);
            Object sub = claims.get("sub");
            return sub == null ? "" : String.valueOf(sub);
        } catch (Exception e) {
            return "";
        }
    }

    private static String pad(String b64) {
        int rem = b64.length() % 4;
        if (rem == 0) {
            return b64;
        }
        return b64 + "====".substring(rem);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(
            String email,
            String userId,
            String accessToken,
            String refreshToken,
            long expiresAtEpochSec,
            String savedAt
    ) {
        public boolean accessExpired(long skewSeconds) {
            return Instant.now().getEpochSecond() + Math.max(0, skewSeconds) >= expiresAtEpochSec;
        }
    }
}
