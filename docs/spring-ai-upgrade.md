# Spring AI 1.1.7 迁移记录

Enterprise Agent Platform 目前使用 Spring AI `1.1.7` 稳定线，搭配 Spring Boot 3.4.5 与 Java 17。迁移通过 `mvn validate compile` 和 `mvn verify` 验证，无需启动 Docker 或模型服务。

## 已应用的兼容性调整

- Spring starter 采用稳定的 `spring-ai-starter-*` 命名规范。
- 向量检索新增 `spring-ai-advisors-vector-store` 依赖，并通过其支持的 builder 构建 `QuestionAnswerAdvisor`。
- `MessageChatMemoryAdvisor` 改用其 builder；自定义记忆存储实现了 `ChatMemory#get(String)`，并保留带边界限制的重载用于针对性单元测试。
- 会话 ID 使用 `ChatMemory.CONVERSATION_ID`。
- `Media` 使用 `org.springframework.ai.content.Media`。
- `TokenTextSplitter` 改用其支持的 builder，不再使用已被移除的五参构造函数。

项目有意不直接跳到 Spring AI 2.0，因为该版本线对这个 Spring Boot 3.4 应用而言不是稳定目标。再次升级需要单独的兼容性评审，并与真实部署的 ragproof 基线做对比。
