package com.enterprise.iqk.agent.research;

/** 深度研究队列打满：提交被拒（对外映射 429 Too Many Requests）。 */
public class ResearchQueueFullException extends RuntimeException {

    public ResearchQueueFullException(String message) {
        super(message);
    }
}
