package com.moex.cointegration.model;

import java.time.LocalDateTime;
import java.util.List;

/** Снимок paper-журнала на диске. */
public record PaperJournal(LocalDateTime updatedAt, List<PaperTradeEntry> entries) {
}
