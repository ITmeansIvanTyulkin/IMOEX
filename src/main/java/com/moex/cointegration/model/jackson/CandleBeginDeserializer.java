package com.moex.cointegration.model.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Читает begin/date свечи: ISO-строка, массив Jackson LocalDate/LocalDateTime, epoch.
 */
public class CandleBeginDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter MOEX_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_STRING) {
            return parseString(p.getText());
        }
        if (t == JsonToken.VALUE_NUMBER_INT) {
            // unlikely; treat as epoch seconds
            return LocalDateTime.ofEpochSecond(p.getLongValue(), 0, java.time.ZoneOffset.UTC);
        }
        if (t == JsonToken.START_ARRAY) {
            int y = p.nextIntValue(0);
            int m = p.nextIntValue(1);
            int d = p.nextIntValue(1);
            JsonToken next = p.nextToken();
            if (next == JsonToken.END_ARRAY) {
                return LocalDate.of(y, m, d).atStartOfDay();
            }
            int hour = p.getIntValue();
            next = p.nextToken();
            int minute = next == JsonToken.END_ARRAY ? 0 : p.getIntValue();
            if (next != JsonToken.END_ARRAY) {
                next = p.nextToken();
            }
            int second = 0;
            if (next != JsonToken.END_ARRAY && next != null) {
                second = p.getIntValue();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    // skip nanos / extras
                }
            }
            return LocalDateTime.of(y, m, d, hour, minute, second);
        }
        return (LocalDateTime) ctxt.handleUnexpectedToken(LocalDateTime.class, p);
    }

    private static LocalDateTime parseString(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.length() >= 19) {
            try {
                return LocalDateTime.parse(v.substring(0, 19), MOEX_DT);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            try {
                return LocalDateTime.parse(v.substring(0, 19));
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        if (v.length() >= 10) {
            return LocalDate.parse(v.substring(0, 10)).atStartOfDay();
        }
        throw new IllegalArgumentException("Unparseable candle begin: " + raw);
    }
}
