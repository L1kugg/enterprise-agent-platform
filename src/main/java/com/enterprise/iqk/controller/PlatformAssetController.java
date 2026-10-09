package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.platform.PlatformAssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
@Validated
public class PlatformAssetController {
    private final PlatformAssetService service;

    public record AssetUpsertRequest(
            @NotBlank(message = "name is required") String name,
            String description,
            String configJson,
            Long parentId) {}

    public record AssetUpdateRequest(String name, String description, String configJson, String status, Long parentId) {}
    public record CopyAgentRequest(String targetName) {}
    public record PublishAgentRequest(List<String> channels) {}

    @PostMapping({"/{assetType}"})
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset create(@PathVariable String assetType, @Valid @RequestBody AssetUpsertRequest request) {
        return service.create(assetType, request.name(), request.description(), request.configJson(),
                request.parentId(), UserContext.currentUserId(null));
    }

    @GetMapping("/{assetType}")
    @PreAuthorize("isAuthenticated()")
    public PagedResult<PlatformAsset> list(@PathVariable String assetType,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int pageSize,
                                           @RequestParam(required = false) String search) {
        return new PagedResult<>(service.list(assetType, search, page, pageSize),
                service.count(assetType, search), Math.max(1, page), Math.max(1, Math.min(pageSize, 100)));
    }

    @GetMapping("/{assetType}/{id}")
    @PreAuthorize("isAuthenticated()")
    public PlatformAsset get(@PathVariable String assetType, @PathVariable Long id) {
        return service.get(assetType, id);
    }

    @PutMapping("/{assetType}/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset update(@PathVariable String assetType, @PathVariable Long id,
                                @Valid @RequestBody AssetUpdateRequest request) {
        return service.update(assetType, id, request.name(), request.description(),
                request.configJson(), request.status(), request.parentId());
    }

    @DeleteMapping("/{assetType}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String assetType, @PathVariable Long id) {
        service.delete(assetType, id);
    }

    @PostMapping("/agents/{id}/copy")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset copyAgent(@PathVariable Long id, @RequestBody(required = false) CopyAgentRequest request) {
        return service.copyAgent(id, request == null ? null : request.targetName());
    }

    @PostMapping("/agents/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset publishAgent(@PathVariable Long id,
                                      @RequestBody(required = false) PublishAgentRequest request) {
        return service.publishAgent(id, request == null ? List.of() : request.channels());
    }

    @PostMapping("/model-services/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset testModel(@PathVariable Long id) {
        return service.testModel(id);
    }
}


