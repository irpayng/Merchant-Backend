package com.tms.report.core.export;

import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Reusable CSV export utility. Controllers call CsvExporter.export() with data
 * and column definitions.
 */
public class CsvExporter {

    /**
     * Formats a numeric value as a currency string with commas and 2 decimal places
     * (e.g. "1,500.00"). Returns empty string for null values.
     */
    public static String formatCurrency(Object value) {
        if (value == null)
            return "";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        if (value instanceof BigDecimal bd)
            return nf.format(bd);
        if (value instanceof Number num)
            return nf.format(num.doubleValue());
        try {
            return nf.format(new BigDecimal(value.toString()));
        } catch (NumberFormatException e) {
            return value.toString();
        }
    }

    public static <T> void export(HttpServletResponse response, String filename, List<T> data, String[] headers,
            Function<T, String[]> rowMapper) throws Exception {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=" + filename + ".csv");
        PrintWriter w = response.getWriter();
        w.println(String.join(",", headers));
        for (T item : data) {
            String[] vals = rowMapper.apply(item);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < vals.length; i++) {
                if (i > 0)
                    line.append(",");
                line.append(csv(vals[i]));
            }
            w.println(line);
        }
        w.flush();
    }

    @SuppressWarnings("unchecked")
    public static void exportMaps(HttpServletResponse response, String filename, List<Map<String, Object>> data,
            String[] headers, String[] keys) throws Exception {
        export(response, filename, data, headers, row -> {
            String[] vals = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                Object v = row.get(keys[i]);
                vals[i] = v != null ? v.toString() : "";
            }
            return vals;
        });
    }

    private static String csv(String val) {
        if (val == null)
            return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
