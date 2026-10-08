package com.moex.trinity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Per-machine durable data root — outside the app install / git tree so candle
 * history and journals survive deleting an old version and installing a new one.
 *
 * <ul>
 *   <li>macOS: {@code ~/Library/Application Support/TRINITY}</li>
 *   <li>Windows: {@code %LOCALAPPDATA%\TRINITY}</li>
 *   <li>Linux: {@code ~/.local/share/trinity}</li>
 * </ul>
 *
 * Config token {@code imoex.data-dir=user} resolves here. Absolute paths and
 * relative {@code data} (dev) are left as-is.
 */
public final class TrinityUserDataPaths {

    public static final String USER_TOKEN = "user";
    public static final String APP_FOLDER = "TRINITY";

    private TrinityUserDataPaths() {
    }

    public static Path defaultDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", APP_FOLDER);
        }
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.isBlank()) {
                local = System.getenv("APPDATA");
            }
            if (local != null && !local.isBlank()) {
                return Path.of(local, APP_FOLDER);
            }
            return Path.of(home, "AppData", "Local", APP_FOLDER);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "trinity");
        }
        return Path.of(home, ".local", "share", "trinity");
    }

    /**
     * Resolve configured {@code imoex.data-dir}:
     * {@code user} / blank → OS app-support; otherwise as given (relative or absolute).
     */
    public static Path resolveDataDir(String configured) {
        if (configured == null || configured.isBlank()
                || USER_TOKEN.equalsIgnoreCase(configured.trim())) {
            return defaultDataDir().toAbsolutePath().normalize();
        }
        return Path.of(configured.trim()).toAbsolutePath().normalize();
    }

    public static boolean isUserToken(String configured) {
        return configured == null || configured.isBlank()
                || USER_TOKEN.equalsIgnoreCase(configured.trim());
    }

    /**
     * One-shot copy of legacy repo-local {@code data/invest-chart.db} into the
     * durable dir when the durable file is still missing.
     */
    public static void migrateInvestChartDbIfNeeded(Path durableDb) {
        if (durableDb == null) {
            return;
        }
        try {
            if (Files.isRegularFile(durableDb)) {
                return;
            }
            Path legacy = Path.of("data", "invest-chart.db").toAbsolutePath().normalize();
            if (!Files.isRegularFile(legacy) || legacy.equals(durableDb)) {
                return;
            }
            Files.createDirectories(durableDb.getParent());
            Files.copy(legacy, durableDb, StandardCopyOption.REPLACE_EXISTING);
            // Best-effort WAL siblings.
            for (String suf : new String[]{"-wal", "-shm"}) {
                Path side = Path.of(legacy.toString() + suf);
                if (Files.isRegularFile(side)) {
                    Files.copy(side, Path.of(durableDb.toString() + suf), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception ignored) {
            // Non-fatal — warm will refill from market data.
        }
    }
}
