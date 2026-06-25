package com.tms.report.modules.upload.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.upload.model.Upload;
import com.tms.report.modules.upload.repository.UploadRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class UploadController {

    private final UploadRepository uploadRepository;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        return PagedResponse
                .from(uploadRepository.findAll(PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/{id}")
    public ApiResponse<Upload> show(@PathVariable Long id) {
        return ApiResponse.success(uploadRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Not found")));
    }

    @LogActivity(action = "upload", description = "{admin} uploaded file '{body.originalName}'")
    @PostMapping
    public ApiResponse<Upload> store(@RequestBody Upload upload) {
        return ApiResponse.success(uploadRepository.save(upload));
    }

    @LogActivity(action = "delete", description = "{admin} deleted upload #{id}")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> destroy(@PathVariable Long id) {
        uploadRepository.deleteById(id);
        return ApiResponse.success(null);
    }
}
