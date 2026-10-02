package com.enterprise.iqk.controller;

import com.enterprise.iqk.agent.research.DeepResearchService;
import com.enterprise.iqk.agent.research.ResearchQueueFullException;
import com.enterprise.iqk.agent.workflow.AgentWorkflowEngine;
import com.enterprise.iqk.agent.workflow.WorkflowTaskVO;
import com.enterprise.iqk.security.ApiKeyOrJwtAuthFilter;
import com.enterprise.iqk.security.AuditLogFilter;
import com.enterprise.iqk.security.HttpMetricsFilter;
import com.enterprise.iqk.security.RateLimitFilter;
import com.enterprise.iqk.security.RequestContextFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 深度研究 API 契约：创建任务异步受理（202 + 受理形状，report 为空）、
 * 队列满 429、报告查询与未知任务 404。
 */
@WebMvcTest(value = DeepResearchController.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ApiKeyOrJwtAuthFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RateLimitFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuditLogFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HttpMetricsFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RequestContextFilter.class)
})
@AutoConfigureMockMvc(addFilters = false)
class DeepResearchControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeepResearchService deepResearchService;

    @MockBean
    private AgentWorkflowEngine workflowEngine;

    @Test
    void createResearchReturnsAcceptedWithPlanningStatus() throws Exception {
        when(deepResearchService.createResearch(any(), any()))
                .thenReturn(DeepResearchService.DeepResearchResult.builder()
                        .taskId("task-1").topic("测试主题").report(null).status("PLANNING").build());

        mockMvc.perform(post("/ai/research/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"测试主题\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("PLANNING"))
                .andExpect(jsonPath("$.report").value(nullValue()));
    }

    @Test
    void createResearchReturns429WhenQueueFull() throws Exception {
        when(deepResearchService.createResearch(any(), any()))
                .thenThrow(new ResearchQueueFullException("深度研究任务队列已满（容量 20），请稍后重试"));

        mockMvc.perform(post("/ai/research/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"测试主题\"}"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.ok").value(0))
                .andExpect(jsonPath("$.msg").value("深度研究任务队列已满（容量 20），请稍后重试"));
    }

    @Test
    void getReportReturns404ForUnknownTask() throws Exception {
        when(workflowEngine.getTask("public", "task-x")).thenReturn(null);

        mockMvc.perform(get("/ai/research/tasks/task-x/report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(0));
    }

    @Test
    void getReportReturnsTaskIdAndReport() throws Exception {
        when(workflowEngine.getTask("public", "task-1")).thenReturn(WorkflowTaskVO.builder()
                .taskId("task-1").tenantId("public").type("DEEP_RESEARCH")
                .status("DONE").finalOutput("# 报告正文").build());

        mockMvc.perform(get("/ai/research/tasks/task-1/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.report").value("# 报告正文"));
    }
}
