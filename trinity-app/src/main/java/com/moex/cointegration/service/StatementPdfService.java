package com.moex.cointegration.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.moex.cointegration.config.CapitalProperties;
import com.moex.cointegration.model.PaperJournal;
import com.moex.cointegration.model.PaperTradeEntry;
import com.moex.cointegration.product.ProductEditionService;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;

/**
 * Builds a downloadable Statement PDF: Trinity header + deposit + strategy books.
 * No browser print dialog — caller streams bytes with Content-Disposition: attachment.
 */
@Service
public class StatementPdfService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter WHEN_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"));

    private static final Color NAVY = new Color(0x1e, 0x2a, 0x32);
    private static final Color MUTED = new Color(0x6a, 0x76, 0x80);
    private static final Color LINE = new Color(0xe4, 0xe9, 0xee);
    private static final Color RING_A = new Color(0x7e, 0xb6, 0xd4);
    private static final Color GOLD = new Color(0xc4, 0xa3, 0x5a);
    private static final Color SLATE = new Color(0x8a, 0x93, 0x9b);
    private static final Color OK = new Color(0x1f, 0x7a, 0x45);
    private static final Color DANGER = new Color(0xc5, 0x30, 0x30);
    private static final Color HEAD_BG = new Color(0xf3, 0xf5, 0xf7);

    private final CapitalProperties capitalProperties;
    private final ProductEditionService productEdition;
    private final PaperTradingService paperTradingService;
    private final Optional<TrendPaperJournalService> trendPaperJournal;
    private final Optional<CalendarArbPaperJournalService> calendarArbJournal;

    private final BaseFont bfRegular;
    private final BaseFont bfBold;

    public StatementPdfService(
            CapitalProperties capitalProperties,
            ProductEditionService productEdition,
            PaperTradingService paperTradingService,
            Optional<TrendPaperJournalService> trendPaperJournal,
            Optional<CalendarArbPaperJournalService> calendarArbJournal
    ) throws IOException, DocumentException {
        this.capitalProperties = capitalProperties;
        this.productEdition = productEdition;
        this.paperTradingService = paperTradingService;
        this.trendPaperJournal = trendPaperJournal;
        this.calendarArbJournal = calendarArbJournal != null ? calendarArbJournal : Optional.empty();
        this.bfRegular = loadFont("fonts/DejaVuSans.ttf");
        this.bfBold = loadFont("fonts/DejaVuSans-Bold.ttf");
    }

    public byte[] exportPdf() throws DocumentException, IOException {
        PaperJournal journal = paperTradingService.summary();
        if (journal == null) {
            journal = new PaperJournal(null, List.of());
        }
        List<PaperTradeEntry> allEntries = journal.entries() == null ? List.of() : journal.entries();
        List<PaperTradeEntry> pairsEntries = allEntries.stream()
                .filter(e -> e.book() == null || e.book().isBlank() || "DAILY".equalsIgnoreCase(e.book()))
                .toList();

        long pairsOpen = pairsEntries.stream().filter(e -> "OPEN".equals(e.status())).count();
        double pairsRealized = pairsEntries.stream()
                .filter(e -> "CLOSED".equals(e.status()) && e.pnlRub() != null)
                .mapToDouble(PaperTradeEntry::pnlRub)
                .sum();
        double pairsUnrealized = pairsEntries.stream()
                .filter(e -> "OPEN".equals(e.status()) && e.unrealizedPnlRub() != null)
                .mapToDouble(PaperTradeEntry::unrealizedPnlRub)
                .sum();

        Map<String, Object> trendSt = Map.of();
        List<Map<String, Object>> trendTrades = List.of();
        if (productEdition.hasTrend() && trendPaperJournal.isPresent()) {
            TrendPaperJournalService svc = trendPaperJournal.get();
            trendSt = svc.statement();
            trendTrades = svc.allTradeDtos();
        }
        double trendRealized = num(trendSt.get("realizedPnlRub"));
        double trendToday = num(trendSt.get("todayPnlRub"));
        int trendClosed = (int) num(trendSt.get("closedCount"));

        Map<String, Object> arbSt = Map.of();
        List<Map<String, Object>> arbTrades = List.of();
        if (productEdition.hasArb() && calendarArbJournal.isPresent()) {
            CalendarArbPaperJournalService svc = calendarArbJournal.get();
            arbSt = svc.statement();
            arbTrades = svc.allTradeDtos();
        }
        double arbRealized = num(arbSt.get("realizedPnlRub"));
        double arbToday = num(arbSt.get("todayPnlRub"));
        int arbClosed = (int) num(arbSt.get("closedCount"));

        double equity = capitalProperties.equityRub() != null ? capitalProperties.equityRub() : 0;
        double depositNet = pairsRealized + pairsUnrealized
                + (productEdition.hasTrend() ? trendRealized : 0)
                + (productEdition.hasArb() ? arbRealized : 0);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 40, 36);
        PdfWriter writer = PdfWriter.getInstance(doc, baos);
        doc.open();

        drawHeader(doc, writer);
        addSpacer(doc, 10);
        addSectionTitle(doc, "Депозит (общий)");
        addKvTable(doc, List.of(
                new String[]{"Equity", formatRub0(equity)},
                new String[]{"Pairs net", formatRub0(pairsRealized + pairsUnrealized)},
                new String[]{"Trend realized", productEdition.hasTrend() ? formatRub0(trendRealized) : "locked"},
                new String[]{"Trend сегодня", productEdition.hasTrend() ? formatRub0(trendToday) : "—"},
                new String[]{"Arb realized", productEdition.hasArb() ? formatRub0(arbRealized) : "locked"},
                new String[]{"Net* (доступное)", formatRub0(depositNet)}
        ));

        addSpacer(doc, 14);
        addSectionTitle(doc, "① Коинтеграция · DAILY");
        addKvTable(doc, List.of(
                new String[]{"Всего", String.valueOf(pairsEntries.size())},
                new String[]{"OPEN", String.valueOf(pairsOpen)},
                new String[]{"CLOSED", String.valueOf(pairsEntries.stream()
                        .filter(e -> "CLOSED".equals(e.status())).count())},
                new String[]{"Realized ₽*", formatRub0(pairsRealized)},
                new String[]{"Unrealized ₽*", formatRub0(pairsUnrealized)},
                new String[]{"Net ₽*", formatRub0(pairsRealized + pairsUnrealized)}
        ));
        if (pairsEntries.isEmpty()) {
            addMuted(doc, "Журнал пуст — нет paper-входов DAILY.");
        } else {
            addPairsTable(doc, pairsEntries);
        }

        addSpacer(doc, 14);
        addSectionTitle(doc, "② Тренд · BR");
        if (!productEdition.hasTrend()) {
            addMuted(doc, "TREND заблокирован в этой редакции.");
        } else {
            addKvTable(doc, List.of(
                    new String[]{"Closed", String.valueOf(trendClosed)},
                    new String[]{"Wins/Losses",
                            (int) num(trendSt.get("wins")) + "/" + (int) num(trendSt.get("losses"))},
                    new String[]{"Realized ₽*", formatRub0(trendRealized)},
                    new String[]{"Сегодня ₽*", formatRub0(trendToday)},
                    new String[]{"Instrument", String.valueOf(trendSt.getOrDefault("instrument", "BR"))}
            ));
            if (trendTrades.isEmpty()) {
                addMuted(doc, "Statement пуст — закрытых paper-сделок BR ещё нет.");
            } else {
                Map<String, List<Map<String, Object>>> byYear = new TreeMap<>((a, b) -> b.compareTo(a));
                for (Map<String, Object> row : trendTrades) {
                    Object daySrc = row.get("closedAt") != null ? row.get("closedAt") : row.get("openedAt");
                    byYear.computeIfAbsent(yearOf(daySrc), k -> new ArrayList<>()).add(row);
                }
                for (Map.Entry<String, List<Map<String, Object>>> e : byYear.entrySet()) {
                    double yearPnl = e.getValue().stream().mapToDouble(r -> num(r.get("pnlRub"))).sum();
                    addMuted(doc, e.getKey() + " · " + e.getValue().size() + " сд. · "
                            + String.format(Locale.ROOT, "%+.0f ₽", yearPnl));
                    addTrendTable(doc, e.getValue());
                }
            }
        }

        addSpacer(doc, 14);
        addSectionTitle(doc, "③ Календарный арбитраж");
        if (!productEdition.hasArb()) {
            addMuted(doc, "ARB заблокирован в этой редакции.");
        } else {
            addKvTable(doc, List.of(
                    new String[]{"Closed", String.valueOf(arbClosed)},
                    new String[]{"Wins/Losses",
                            (int) num(arbSt.get("wins")) + "/" + (int) num(arbSt.get("losses"))},
                    new String[]{"Realized ₽*", formatRub0(arbRealized)},
                    new String[]{"Сегодня ₽*", formatRub0(arbToday)}
            ));
            if (arbTrades.isEmpty()) {
                addMuted(doc, "Statement пуст — закрытых calendar-спредов ещё нет.");
            } else {
                addTrendTable(doc, arbTrades);
            }
        }

        addSpacer(doc, 16);
        Paragraph foot = new Paragraph(
                "TRINITY — research / decision-support. Не индивидуальная инвестиционная рекомендация. "
                        + "Statement PnL — research-метрика (qty×цена, не брокерский отчёт). "
                        + "Проприетарное ПО · регистрация в Роспатенте · см. LICENSE.",
                font(8, false, MUTED));
        foot.setLeading(11f);
        doc.add(foot);

        doc.close();
        return baos.toByteArray();
    }

    private void drawHeader(Document doc, PdfWriter writer) throws DocumentException {
        float pageW = doc.getPageSize().getWidth();
        float left = doc.left();
        float top = doc.getPageSize().getHeight() - 28;

        PdfContentByte cb = writer.getDirectContent();
        drawLogoRings(cb, left + 14, top - 28);

        Paragraph brand = new Paragraph();
        brand.setIndentationLeft(48);
        brand.add(new Phrase("TRINITY\n", font(18, true, NAVY)));
        brand.add(new Phrase("Multi-Strategy Arbitrage\n", font(10, false, MUTED)));
        brand.add(new Phrase("Three Strategies. One Mission.", font(9, false, GOLD)));
        brand.setLeading(15f);
        doc.add(brand);

        addSpacer(doc, 8);
        Paragraph title = new Paragraph("Statement · депозит и стратегии", font(13, true, NAVY));
        doc.add(title);
        String when = ZonedDateTime.now(MSK).format(WHEN_FMT) + " (МСК)";
        Paragraph meta = new Paragraph("Выгрузка: " + when, font(9, false, MUTED));
        meta.setSpacingBefore(2);
        meta.setSpacingAfter(6);
        doc.add(meta);

        cb.setColorStroke(LINE);
        cb.setLineWidth(0.8f);
        float y = writer.getVerticalPosition(true) - 4;
        cb.moveTo(left, y);
        cb.lineTo(pageW - doc.rightMargin(), y);
        cb.stroke();
        addSpacer(doc, 8);
    }

    private void drawLogoRings(PdfContentByte cb, float cx, float cy) {
        float r = 9f;
        strokeRing(cb, cx - 7, cy - 2, r, RING_A);
        strokeRing(cb, cx + 7, cy - 2, r, GOLD);
        strokeRing(cb, cx, cy + 6, r, SLATE);
    }

    private static void strokeRing(PdfContentByte cb, float cx, float cy, float r, Color color) {
        cb.setColorStroke(color);
        cb.setLineWidth(2.2f);
        cb.circle(cx, cy, r);
        cb.stroke();
    }

    private void addPairsTable(Document doc, List<PaperTradeEntry> entries) throws DocumentException {
        PdfPTable t = new PdfPTable(new float[]{1.1f, 2.2f, 1.2f, 1.4f, 1.1f, 1.2f, 1.2f, 1.6f, 1.6f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        headerCell(t, "Статус");
        headerCell(t, "Пара");
        headerCell(t, "Сигнал");
        headerCell(t, "Decision");
        headerCell(t, "Entry Z");
        headerCell(t, "Mark/Exit Z");
        headerCell(t, "PnL ₽*");
        headerCell(t, "Opened");
        headerCell(t, "Closed");
        for (PaperTradeEntry e : entries) {
            Double markOrExit = e.exitZ() != null ? e.exitZ() : e.markZ();
            Double rub = e.pnlRub() != null ? e.pnlRub() : e.unrealizedPnlRub();
            bodyCell(t, nz(e.status()));
            bodyCell(t, nz(e.tickerY()) + " / " + nz(e.tickerX()));
            bodyCell(t, e.signal() == null ? "—" : e.signal().name());
            bodyCell(t, e.decision() == null ? "—" : e.decision().name());
            bodyCell(t, formatZ(e.entryZ()));
            bodyCell(t, markOrExit == null ? "—" : formatZ(markOrExit));
            bodyCellColored(t, rub == null ? "—" : formatRub0(rub), rub);
            bodyCell(t, e.openedAt() == null ? "—" : shortIso(e.openedAt().toString()));
            bodyCell(t, e.closedAt() == null ? "—" : shortIso(e.closedAt().toString()));
        }
        doc.add(t);
    }

    private void addTrendTable(Document doc, List<Map<String, Object>> trades) throws DocumentException {
        PdfPTable t = new PdfPTable(new float[]{1.4f, 1.1f, 1.1f, 1f, 0.8f, 1.6f, 1.2f, 1.4f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        headerCell(t, "Дата");
        headerCell(t, "Вход");
        headerCell(t, "Выход");
        headerCell(t, "Side");
        headerCell(t, "Qty");
        headerCell(t, "Reason");
        headerCell(t, "PnL");
        headerCell(t, "Tag");
        for (Map<String, Object> row : trades) {
            double pnl = num(row.get("pnlRub"));
            Object daySrc = row.get("closedAt") != null ? row.get("closedAt") : row.get("openedAt");
            bodyCell(t, shortDate(daySrc));
            bodyCell(t, shortIso(row.get("openedAt")));
            bodyCell(t, shortIso(row.get("closedAt")));
            bodyCell(t, String.valueOf(row.getOrDefault("side", "—")));
            bodyCell(t, String.valueOf(row.getOrDefault("qty", "—")));
            bodyCell(t, String.valueOf(row.getOrDefault("exitReason", "—")));
            bodyCellColored(t, String.format(Locale.ROOT, "%+.0f ₽", pnl), pnl);
            bodyCell(t, String.valueOf(row.getOrDefault("tag", "—")));
        }
        doc.add(t);
    }

    private void addKvTable(Document doc, List<String[]> rows) throws DocumentException {
        PdfPTable t = new PdfPTable(new float[]{1.4f, 2f});
        t.setWidthPercentage(70);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        t.setSpacingBefore(4);
        for (String[] row : rows) {
            PdfPCell k = cell(row[0], font(9, true, MUTED), Element.ALIGN_LEFT);
            k.setBorderColor(LINE);
            k.setPadding(4);
            PdfPCell v = cell(row[1], font(9, false, NAVY), Element.ALIGN_LEFT);
            v.setBorderColor(LINE);
            v.setPadding(4);
            t.addCell(k);
            t.addCell(v);
        }
        doc.add(t);
    }

    private void headerCell(PdfPTable t, String text) {
        PdfPCell c = cell(text, font(8, true, NAVY), Element.ALIGN_LEFT);
        c.setBackgroundColor(HEAD_BG);
        c.setBorderColor(LINE);
        c.setPadding(4);
        t.addCell(c);
    }

    private void bodyCell(PdfPTable t, String text) {
        PdfPCell c = cell(text, font(8, false, NAVY), Element.ALIGN_LEFT);
        c.setBorderColor(LINE);
        c.setPadding(3.5f);
        t.addCell(c);
    }

    private void bodyCellColored(PdfPTable t, String text, Double value) {
        Color ink = NAVY;
        if (value != null) {
            if (value > 0) {
                ink = OK;
            } else if (value < 0) {
                ink = DANGER;
            }
        }
        PdfPCell c = cell(text, font(8, false, ink), Element.ALIGN_LEFT);
        c.setBorderColor(LINE);
        c.setPadding(3.5f);
        t.addCell(c);
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "—" : text, font));
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private void addSectionTitle(Document doc, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, font(12, true, NAVY));
        p.setSpacingAfter(2);
        doc.add(p);
    }

    private void addMuted(Document doc, String text) throws DocumentException {
        Paragraph p = new Paragraph(text, font(9, false, MUTED));
        p.setSpacingBefore(4);
        doc.add(p);
    }

    private void addSpacer(Document doc, float pt) throws DocumentException {
        Paragraph p = new Paragraph(" ");
        p.setLeading(pt);
        doc.add(p);
    }

    private Font font(float size, boolean bold, Color color) {
        return new Font(bold ? bfBold : bfRegular, size, Font.NORMAL, color);
    }

    private static BaseFont loadFont(String classpath) throws IOException, DocumentException {
        try (InputStream in = StatementPdfService.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IOException("Missing font on classpath: " + classpath);
            }
            byte[] bytes = in.readAllBytes();
            String name = classpath.contains("/") ? classpath.substring(classpath.lastIndexOf('/') + 1) : classpath;
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        }
    }

    private static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String formatRub0(double v) {
        return String.format(Locale.ROOT, "%.0f ₽", v);
    }

    private static String formatZ(double z) {
        return String.format(Locale.ROOT, "%.2f", z);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String shortIso(Object iso) {
        if (iso == null) {
            return "—";
        }
        String s = String.valueOf(iso);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("T(\\d{2}:\\d{2})").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    private static String shortDate(Object iso) {
        if (iso == null) {
            return "—";
        }
        String s = String.valueOf(iso).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})-(\\d{2})-(\\d{2})")
                .matcher(s);
        if (m.find()) {
            return m.group(3) + "." + m.group(2) + "." + m.group(1);
        }
        return s.length() > 10 ? s.substring(0, 10) : s;
    }

    private static String yearOf(Object iso) {
        if (iso == null) {
            return "—";
        }
        String s = String.valueOf(iso).trim();
        if (s.length() >= 4 && Character.isDigit(s.charAt(0))) {
            return s.substring(0, 4);
        }
        return "—";
    }
}
