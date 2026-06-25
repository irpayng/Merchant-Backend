package com.tms.report.core.export;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Base Excel exporter mirroring Laravel's Export class. Subclasses provide
 * headings and row data.
 */
public abstract class ExcelExporter {

    public abstract String[] headings();

    public abstract List<Object[]> rows();

    public void export(HttpServletResponse response, String filename) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");

            // Header row
            Row headerRow = sheet.createRow(0);
            String[] headings = headings();
            for (int i = 0; i < headings.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headings[i]);
            }

            // Data rows
            List<Object[]> data = rows();
            for (int i = 0; i < data.size(); i++) {
                Row row = sheet.createRow(i + 1);
                Object[] values = data.get(i);
                for (int j = 0; j < values.length; j++) {
                    Cell cell = row.createCell(j);
                    cell.setCellValue(values[j] != null ? values[j].toString() : "N/A");
                }
            }

            workbook.write(response.getOutputStream());
        }
    }
}
