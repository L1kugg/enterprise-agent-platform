package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.platform.PlatformAsset;
import com.enterprise.iqk.domain.platform.PlatformRoleRecord;
import com.enterprise.iqk.domain.platform.PlatformUserRecord;
import com.enterprise.iqk.domain.vo.PagedResult;
import com.enterprise.iqk.security.UserContext;
import com.enterprise.iqk.service.platform.PlatformAdminService;
import com.enterprise.iqk.service.platform.PlatformAssetService;
import com.enterprise.iqk.service.platform.PlatformSafetyGuardService;
import com.enterprise.iqk.service.platform.PlatformSqlTestService;
import com.enterprise.iqk.service.platform.PlatformToolTestService;
import com.enterprise.iqk.service.platform.PlatformWorkflowService;
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
import java.util.Map;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
@Validated
public class PlatformAssetController {
    private final PlatformAssetService service;
    private final PlatformWorkflowService workflowService;
    private final PlatformToolTestService toolTestService;
    private final PlatformSafetyGuardService safetyGuardService;
    private final PlatformSqlTestService sqlTestService;
    private final PlatformAdminService adminService;

    public record AssetUpsertRequest(
            @NotBlank(message = "name is required") String name,
            String description,
            String configJson,
            Long parentId) {}

    public record AssetUpdateRequest(String name, String description, String configJson, String status, Long parentId) {}
    public record CopyAgentRequest(String targetName) {}
    public record PublishAgentRequest(List<String> channels) {}
    public record CopyWorkflowRequest(String targetName) {}
    public record WorkflowTestRunRequest(Map<String, Object> input) {}
    public record ToolTestRequest(Map<String, Object> params) {}
    public record SafetyGuardTestRequest(String text) {}
    public record RoleUpsertRequest(@NotBlank(message = "name is required") String name, String code,
                                    String description, List<String> permissions) {}
    public record UserUpdateRequest(String displayName, String status, List<String> roles) {}

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

    @PostMapping("/workflows/{id}/copy")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset copyWorkflow(@PathVariable Long id,
                                      @RequestBody(required = false) CopyWorkflowRequest request) {
        return workflowService.copy(id, request == null ? null : request.targetName());
    }

    @PostMapping("/workflows/{id}/online")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset onlineWorkflow(@PathVariable Long id) {
        return workflowService.online(id);
    }

    @PostMapping("/workflows/{id}/offline")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset offlineWorkflow(@PathVariable Long id) {
        return workflowService.offline(id);
    }

    @PostMapping("/workflows/{id}/test-run")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> testRunWorkflow(@PathVariable Long id,
                                               @RequestBody(required = false) WorkflowTestRunRequest request) {
        return workflowService.testRun(id, request == null ? Map.of() : request.input());
    }

    @GetMapping("/workflows/{id}/export")
    @PreAuthorize("isAuthenticated()")
    public com.fasterxml.jackson.databind.JsonNode exportWorkflow(@PathVariable Long id) {
        return workflowService.export(id);
    }

    @PostMapping("/workflows/import")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset importWorkflow(@Valid @RequestBody AssetUpsertRequest request) {
        return workflowService.importWorkflow(request.name(), request.description(), request.configJson());
    }

    @PostMapping("/tools/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> testTool(@PathVariable Long id,
                                        @RequestBody(required = false) ToolTestRequest request) {
        return toolTestService.test(id, request == null ? Map.of() : request.params());
    }

    @PostMapping("/safety-guards/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> testSafetyGuard(@PathVariable Long id,
                                               @RequestBody SafetyGuardTestRequest request) {
        return safetyGuardService.test(id, request.text());
    }

    @PostMapping("/databases/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> testDatabase(@PathVariable Long id) {
        return sqlTestService.testDatabase(id);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResult<PlatformUserRecord> listUsers(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     @RequestParam(required = false) String search) {
        return new PagedResult<>(adminService.listUsers(search, page, pageSize),
                adminService.countUsers(search), Math.max(1, page), Math.max(1, Math.min(pageSize, 100)));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformUserRecord updateUser(@PathVariable Long id,
                                         @Valid @RequestBody UserUpdateRequest request) {
        return adminService.updateUser(id, request.displayName(), request.status(), request.roles());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PlatformRoleRecord> listRoles() {
        return adminService.listRoles();
    }

    @PostMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformRoleRecord createRole(@Valid @RequestBody RoleUpsertRequest request) {
        return adminService.createRole(request.code(), request.name(), request.description(), request.permissions());
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformRoleRecord updateRole(@PathVariable Long id,
                                         @Valid @RequestBody RoleUpsertRequest request) {
        return adminService.updateRole(id, request.name(), request.description(), request.permissions());
    }

    @DeleteMapping("/roles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteRole(@PathVariable Long id) {
        adminService.deleteRole(id);
    }
    @PostMapping("/model-services/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformAsset testModel(@PathVariable Long id) {
        return service.testModel(id);
    }
}


