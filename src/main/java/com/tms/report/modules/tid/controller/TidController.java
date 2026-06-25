package com.tms.report.modules.tid.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.core.filter.QueryFilterHelper;
import com.tms.report.core.security.TenantScope;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.grpc.service.ConfigHttpClient;
import com.tms.report.modules.grpc.service.GrpcClient;
import com.tms.report.modules.tid.model.Tid;
import com.tms.report.modules.tid.repository.TidRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/tids")
@RequiredArgsConstructor
public class TidController {

    private final TidRepository tidRepository;
    private final ConfigHttpClient configHttpClient;
    private final GrpcClient grpcClient;
    private final TenantScope tenantScope;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));

        Specification<Tid> spec = buildSpec(params);
        var pageable = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PagedResponse.from(tidRepository.findAll(spec, pageable));
    }

    private Specification<Tid> buildSpec(Map<String, String> params) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Per-bank tenant scope: a bank sees only TIDs it issued.
            if (!tenantScope.isGlobal()) {
                String bank = tenantScope.bankCode();
                if (bank == null || bank.isBlank()) {
                    return cb.disjunction(); // unmapped non-global → nothing
                }
                predicates.add(cb.equal(root.get("bankCode"), bank));
            }

            String terminalId = trim(params.get("terminal_id"));
            if (terminalId != null) {
                predicates.add(cb.like(cb.lower(root.get("terminalId")), "%" + terminalId.toLowerCase() + "%"));
            }

            String merchantId = trim(params.get("merchant_id"));
            if (merchantId != null) {
                predicates.add(cb.like(cb.lower(root.get("merchantId")), "%" + merchantId.toLowerCase() + "%"));
            }

            String internal = trim(params.get("internal"));
            if (internal != null) {
                predicates.add(cb.equal(root.get("internal"), Boolean.parseBoolean(internal)));
            }

            String search = trim(params.get("search"));
            if (search != null) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("terminalId")), pattern),
                        cb.like(cb.lower(root.get("merchantId")), pattern),
                        cb.like(cb.lower(root.get("merchantName")), pattern),
                        cb.like(cb.lower(root.get("bankAccNo")), pattern)));
            }

            LocalDateTime[] dates = QueryFilterHelper.extractDates(params);
            if (dates[0] != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dates[0]));
            }
            if (dates[1] != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), dates[1]));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @GetMapping("/download-sample")
    public void downloadSample(HttpServletResponse response) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream("sample/tids_sample.xlsx");
        if (stream == null) {
            response.sendError(404, "Sample file not found");
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=tid_sample_document.xlsx");
        stream.transferTo(response.getOutputStream());
    }

    @GetMapping("/{id}")
    public ApiResponse<Tid> show(@PathVariable Long id) {
        Tid tid = tidRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Not found"));
        if (!tenantScope.isGlobal()) {
            String bank = tenantScope.bankCode();
            if (bank == null || !bank.equals(tid.getBankCode())) {
                throw new EntityNotFoundException("Not found");
            }
        }
        return ApiResponse.success(tid);
    }

    @LogActivity(action = "create", description = "{admin} uploaded TIDs file")
    @PostMapping(consumes = "multipart/form-data")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> store(@RequestParam("file") MultipartFile file,
            @RequestParam(value = "internal", defaultValue = "false") boolean internal,
            @RequestParam(value = "processor", required = false) String processor) {
        if (file.isEmpty()) {
            throw new RuntimeException("File is required");
        }
        String filename = file.getOriginalFilename();
        if (filename == null
                || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls") && !filename.endsWith(".csv"))) {
            throw new RuntimeException("File must be xlsx, xls, or csv");
        }
        var result = configHttpClient.uploadTids(file, internal, processor, tenantScope.bankCode());
        return ApiResponse.success((Map<String, Object>) result.get("data"));
    }

    @LogActivity(action = "delete", description = "{admin} deleted TID #{id}")
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> destroy(@PathVariable Long id) {
        return ApiResponse.success(grpcClient.deleteTid(id.toString()));
    }

    @LogActivity(action = "toggleInternal", description = "{admin} toggled internal status of TID #{id}")
    @PostMapping("/{id}/toggle-internal")
    public ApiResponse<Map<String, Object>> toggleInternal(@PathVariable Long id) {
        Tid tid = tidRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Not found"));
        boolean newInternal = !Boolean.TRUE.equals(tid.getInternal());
        return ApiResponse.success(grpcClient.updateTid(id.toString(), Map.of("internal", newInternal)));
    }

    @LogActivity(action = "setProcessor", description = "{admin} set processor scope of TID #{id}")
    @PostMapping("/{id}/processor")
    public ApiResponse<Map<String, Object>> setProcessor(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        tidRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Not found"));
        // Normalise to a comma-separated lowercase list; blank clears the scope so
        // the TID is usable by any processor again.
        String processor = body.get("processor") != null ? body.get("processor").toString().trim() : "";
        return ApiResponse.success(grpcClient.updateTid(id.toString(), Map.of("processor", processor)));
    }
}
