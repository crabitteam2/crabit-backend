package com.crabit.backend.api;

import com.crabit.backend.history.HistoricalBalanceException;
import com.crabit.backend.history.HistoricalPeriods;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.Set;
import java.util.UUID;

final class HistoricalBalanceRequestParser {
    private static final Set<String> QUERIES = Set.of("fromDate", "toDateExclusive", "granularity", "asOfRevision");
    record Request(LocalDate from, LocalDate to, HistoricalPeriods.Granularity granularity, String revision) {}
    private HistoricalBalanceRequestParser() {}
    static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw HistoricalBalanceException.malformed();
        return UUID.fromString(value);
    }
    static Request parse(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1)
                throw HistoricalBalanceException.malformed();
        } catch (IOException e) { throw HistoricalBalanceException.malformed(); }
        if (!QUERIES.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1 || v[0] == null || v[0].isEmpty() || "null".equals(v[0])))
            throw HistoricalBalanceException.malformed();
        try {
            LocalDate from = date(request.getParameter("fromDate"));
            LocalDate to = date(request.getParameter("toDateExclusive"));
            String g = request.getParameter("granularity");
            if (g == null) throw HistoricalBalanceException.malformed();
            var granularity = HistoricalPeriods.Granularity.valueOf(g);
            HistoricalPeriods.validate(from, to, granularity);
            return new Request(from, to, granularity, request.getParameter("asOfRevision"));
        } catch (IllegalArgumentException e) { throw HistoricalBalanceException.malformed(); }
    }
    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw HistoricalBalanceException.malformed();
        try { return LocalDate.parse(value); }
        catch (DateTimeException e) { throw HistoricalBalanceException.malformed(); }
    }
}
