package com.enterprise.iqk.controller;

import com.enterprise.iqk.domain.vo.Result;
import com.enterprise.iqk.ingestion.ChatAttachmentService;
import com.enterprise.iqk.security.TenantContext;
import com.enterprise.iqk.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 聊天内临时附件：解析纯文本短存 30 分钟，回 attachmentId 供 /ai/react 请求携带。
 * 附件不入知识库不入向量索引，用完即弃；权限与会话写侧同口径（PERM_CHAT_WRITE）。
 */
@RestController
@RequestMapping("/ai/chat-attachments")
@RequiredArgsConstructor
public class ChatAttachmentController {

    private final ChatAttachmentService chatAttachmentService;

    /** 上传并解析临时附件；安全扫描不通过或无法提取文本时 400 带可修复文案。 */
    @PostMapping
    public Result upload(@RequestParam("file") MultipartFile file) {
        String tenantId = TenantContext.currentTenantId();
        String userKey = UserContext.currentUserId(null);
        ChatAttachmentService.ParsedAttachment parsed = chatAttachmentService.parse(tenantId, userKey, file);
        Result result = Result.ok();
        result.setData(Map.of(
                "attachmentId", parsed.attachmentId(),
                "filename", parsed.filename(),
                "chars", parsed.chars(),
                "truncated", parsed.truncated()
        ));
        return result;
    }
}
