package com.tms.report.modules.bank.controller;

import com.tms.report.core.dto.ApiResponse;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bank reference list, sourced from the replicated {@code bank_codes}
 * table in {@code tms_report_java}. The transactions filter and metadata
 * bank-name resolution on the UI call {@code GET /banks}; without this they
 * surface a 500. No write surface — banks are reference data here.
 */
@RestController
@RequestMapping("/banks")
@RequiredArgsConstructor
public class BankController {

    private final EntityManager entityManager;

    @GetMapping
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public ApiResponse<List<Map<String, Object>>> index() {
        List<Object[]> rows = entityManager.createNativeQuery("SELECT code, name FROM bank_codes ORDER BY name")
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", row[0]);
            m.put("name", row[1]);
            out.add(m);
        }
        return ApiResponse.success(out);
    }
}
