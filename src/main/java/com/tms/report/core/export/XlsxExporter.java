package com.tms.report.core.export;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Reusable XLSX export utility, mirroring {@link CsvExporter#export} but
 * writing a real {@code .xlsx} workbook with every cell as a
 * <strong>text</strong> cell.
 *
 * <p>
 * This exists because Excel auto-coerces long numeric strings in a CSV (session
 * ids, RRNs, account/meter numbers) into scientific notation and rounds away
 * precision beyond 15 digits — and strips leading zeros. Writing the value as
 * an explicit string cell makes every spreadsheet (Excel, Numbers, Sheets,
 * LibreOffice) render it verbatim, so reconciliation ids stay intact.
 *
 * <p>
 * Uses a <strong>streaming</strong> {@link SXSSFWorkbook} that keeps only a
 * small sliding window of rows in memory and flushes the rest to compressed
 * temp files. A non-streaming {@code XSSFWorkbook} buffers the entire workbook
 * in heap, which OOM-killed the (single-replica, low-heap) admin pod on a
 * 10k-row export. {@link SXSSFWorkbook#dispose()} cleans up the temp files.
 */
public class XlsxExporter {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Rows kept in memory before being flushed to a temp file. */
    private static final int WINDOW = 200;

    public static <T> void export(HttpServletResponse response, String filename, List<T> data, String[] headers,
            Function<T, String[]> rowMapper) throws Exception {
        stream(response, filename, headers, sink -> {
            for (T item : data) {
                sink.row(rowMapper.apply(item));
            }
        });
    }

    /**
     * Map-keyed paged streaming variant (mirrors {@link #exportMaps}): pages
     * through the result set, emitting the given keys from each row map.
     */
    public static void streamPagedMaps(HttpServletResponse response, String filename, String[] headers, int pageSize,
            PageFetcher<Map<String, Object>> fetcher, String[] keys) throws Exception {
        streamPaged(response, filename, headers, pageSize, fetcher, row -> {
            String[] vals = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                Object v = row.get(keys[i]);
                vals[i] = v != null ? v.toString() : "";
            }
            return vals;
        });
    }

    /** Sink for pushing one already-stringified row into the streamed workbook. */
    @FunctionalInterface
    public interface RowSink {
        void row(String[] values);
    }

    /**
     * Fetches one zero-based page of {@code size} rows; empty/short batch ends the
     * stream.
     */
    @FunctionalInterface
    public interface PageFetcher<T> {
        List<T> fetch(int page, int size);
    }

    /**
     * Streams an arbitrarily large result set into an xlsx by paging through it in
     * fixed batches — so there is no row cap and memory stays bounded (one page +
     * the SXSSF window at a time). Reuses each module's existing paginated query
     * (its {@code index()}/{@code findAll()}) and row-mapper, so no per-module
     * query rewrite is needed. This is the single shared export driver for the
     * whole admin backend.
     */
    public static <T> void streamPaged(HttpServletResponse response, String filename, String[] headers, int pageSize,
            PageFetcher<T> fetcher, Function<T, String[]> mapper) throws Exception {
        stream(response, filename, headers, sink -> {
            for (int page = 0;; page++) {
                List<T> batch = fetcher.fetch(page, pageSize);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (T item : batch) {
                    sink.row(mapper.apply(item));
                }
                if (batch.size() < pageSize) {
                    break;
                }
            }
        });
    }

    /**
     * Map-keyed convenience variant mirroring {@code CsvExporter.exportMaps}: emits
     * the given keys from each row map, in order.
     */
    public static void exportMaps(HttpServletResponse response, String filename, List<Map<String, Object>> data,
            String[] headers, String[] keys) throws Exception {
        stream(response, filename, headers, sink -> {
            for (Map<String, Object> row : data) {
                String[] vals = new String[keys.length];
                for (int i = 0; i < keys.length; i++) {
                    Object v = row.get(keys[i]);
                    vals[i] = v != null ? v.toString() : "";
                }
                sink.row(vals);
            }
        });
    }

    /**
     * Streaming variant: the caller pushes rows through the {@link RowSink} (e.g.
     * straight from a DB cursor), so neither the source rows nor the workbook are
     * ever fully held in memory. Rows are flushed to compressed temp files as they
     * arrive; the workbook is written to the response only after {@code body}
     * completes.
     */
    public static void stream(HttpServletResponse response, String filename, String[] headers,
            java.util.function.Consumer<RowSink> body) throws Exception {
        response.setContentType(CONTENT_TYPE);
        response.setHeader("Content-Disposition", "attachment; filename=" + filename + ".xlsx");

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("Data");

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            int[] rowIdx = {1};
            body.accept(values -> {
                Row row = sheet.createRow(rowIdx[0]++);
                for (int c = 0; c < values.length; c++) {
                    row.createCell(c).setCellValue(values[c] != null ? values[c] : "");
                }
            });

            workbook.write(response.getOutputStream());
        }
    }

    /**
     * Streams a sample/template xlsx with headers and a few example rows. Used for
     * bulk-upload templates.
     */
    public static void streamSample(HttpServletResponse response, String filename, String[] headers,
            List<String[]> sampleRows) throws Exception {
        stream(response, filename, headers, sink -> {
            for (String[] row : sampleRows) {
                sink.row(row);
            }
        });
    }
