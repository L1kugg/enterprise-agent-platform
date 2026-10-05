package com.enterprise.iqk.security;

import com.enterprise.iqk.config.properties.RateLimitProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitFilterTest {

    @Test
    void shouldBlockWhenTooManyRequests() throws ServletException, IOException {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setCapacity(1);
        props.setRefillTokens(1);
        props.setRefillSeconds(60);

        RateLimitFilter filter = new RateLimitFilter(props);

        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/ai/chat");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(req1, resp1, new MockFilterChain());
        assertEquals(200, resp1.getStatus());

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/ai/chat");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(req2, resp2, new MockFilterChain());
        assertEquals(429, resp2.getStatus());
        // 手写 429 JSON 须按 UTF-8 编码，中文 msg 不能退化成问号
        assertEquals("{\"ok\":0,\"msg\":\"请求太频繁了，请稍后再试\"}", resp2.getContentAsString());
    }
}
