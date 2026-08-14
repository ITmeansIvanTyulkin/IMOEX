package com.moex.cointegration.controller;

import com.moex.cointegration.service.StatementPdfService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Direct Statement PDF download (attachment) — no browser print dialog.
 */
@RestController
@RequestMapping("/api/statement")
public class StatementExportController {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final StatementPdfService statementPdfService;

    public StatementExportController(StatementPdfService statementPdfService) {
        this.statementPdfService = statementPdfService;
    }

    @GetMapping(value = "/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf() throws Exception {
        byte[] pdf = statementPdfService.exportPdf();
        String filename = "TRINITY-statement-" + LocalDate.now(MSK) + ".pdf";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }
}
