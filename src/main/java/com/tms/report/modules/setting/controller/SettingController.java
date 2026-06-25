package com.tms.report.modules.setting.controller;

import com.tms.report.core.dto.ApiResponse;
import com.tms.report.core.dto.PagedResponse;
import com.tms.report.modules.activity.annotation.LogActivity;
import com.tms.report.modules.setting.model.Setting;
import com.tms.report.modules.setting.repository.SettingRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingController {

    private final SettingRepository settingRepository;

    @GetMapping
    public Map<String, Object> index(@RequestParam Map<String, String> params) {
        int page = Integer.parseInt(params.getOrDefault("page", "1")) - 1;
        int limit = Integer.parseInt(params.getOrDefault("limit", "15"));
        return PagedResponse.from(
                settingRepository.findAll(PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @GetMapping("/{id}")
    public ApiResponse<Setting> show(@PathVariable Long id) {
        return ApiResponse.success(settingRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Not found")));
    }

    @LogActivity(action = "create", description = "{admin} created setting '{body.key}'")
    @PostMapping
    public ApiResponse<Setting> store(@RequestBody Setting setting) {
        return ApiResponse.success(settingRepository.save(setting));
    }

    @LogActivity(action = "update", description = "{admin} updated setting #{id}")
    @PutMapping("/{id}")
    public ApiResponse<Setting> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Setting setting = settingRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Not found"));
        if (body.containsKey("key"))
            setting.setKey(body.get("key"));
        if (body.containsKey("value"))
            setting.setValue(body.get("value"));
        return ApiResponse.success(settingRepository.save(setting));
    }

    @LogActivity(action = "delete", description = "{admin} deleted setting #{id}")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> destroy(@PathVariable Long id) {
        settingRepository.deleteById(id);
        return ApiResponse.success(null);
    }
}
