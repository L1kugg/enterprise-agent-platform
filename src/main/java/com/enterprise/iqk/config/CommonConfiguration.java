package com.enterprise.iqk.config;

import com.enterprise.iqk.constants.SystemConstants;
import com.enterprise.iqk.memory.MemoryInjectionAdvisor;
import com.enterprise.iqk.tools.CourseTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfiguration {

    @Bean
    public ChatClient chatClient(OpenAiChatModel model, ChatMemory chatMemory,
                                 MemoryInjectionAdvisor memoryInjectionAdvisor) {
        return ChatClient
                .builder(model)
                .defaultOptions(ChatOptions.builder().model("qwen-omni-turbo").build())
                .defaultAdvisors(new PassThroughLoggerAdvisor())//帮我记录日志
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())//增强器，MessageChatMemoryAdvisor：帮我们存储对话的上下文
                .defaultAdvisors(memoryInjectionAdvisor)//记忆注入：传 MEMORY_TENANT/USER 参数的链路在请求组装期插入"已知记忆"system 消息
                .defaultSystem("你是一位企业智能助手，专注于知识检索、业务数据查询和任务协助。语气专业、简洁、面向决策。")
                .build();
    }

    @Bean
    public ChatClient serviceChatClient(OpenAiChatModel model,
                                        ChatMemory chatMemory,
                                        CourseTools courseTools,
                                        MemoryInjectionAdvisor memoryInjectionAdvisor) {
        return ChatClient
                .builder(model)
                .defaultSystem(SystemConstants.CUSTOMER_SERVICE_SYSTEM)
                .defaultTools(courseTools)
                .defaultAdvisors(new PassThroughLoggerAdvisor())//帮我记录日志
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())//增强器，MessageChatMemoryAdvisor：帮我们存储对话的上下文
                .defaultAdvisors(memoryInjectionAdvisor)//记忆注入（同 chatClient）
                .build();
    }

    @Bean
    public ChatClient pdfChatClient(OpenAiChatModel model,
                                        ChatMemory chatMemory,
                                    VectorStore vectorStore,
                                    MemoryInjectionAdvisor memoryInjectionAdvisor) {
        return ChatClient
                .builder(model)
                .defaultSystem("请严格按照上下文的内容进行回答，如果上下文里面没有类似内容，就回答没匹配到数据库")
                .defaultAdvisors(new PassThroughLoggerAdvisor())//帮我记录日志
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())//增强器，MessageChatMemoryAdvisor：帮我们存储对话的上下文
                .defaultAdvisors(memoryInjectionAdvisor)//记忆注入（同 chatClient）
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder()
                                .topK(2)
                                .similarityThreshold(0.5)//阈值
                                .build())
                        .build())
                .build();
    }

    /** Agent 内部推理专用客户端：多步规划/汇总调用没有会话语义，不挂 MessageChatMemoryAdvisor（它要求 CONVERSATION_ID，缺失会断言失败断流） */
    @Bean
    public ChatClient agentChatClient(OpenAiChatModel model, MemoryInjectionAdvisor memoryInjectionAdvisor) {
        return ChatClient
                .builder(model)
                .defaultAdvisors(new PassThroughLoggerAdvisor())//帮我记录日志
                .defaultAdvisors(memoryInjectionAdvisor)//记忆注入：仍支持 MEMORY_TENANT/USER 参数
                .build();
    }

    @Bean
    public ChatMemory chatMemory(MysqlChatMemory mysqlChatMemory) {
        return mysqlChatMemory;
    }

}
