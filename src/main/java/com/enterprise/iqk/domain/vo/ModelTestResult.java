package com.enterprise.iqk.domain.vo;

/**
 * 模型服务连接测试结果：探测状态（AVAILABLE/UNAVAILABLE）+ 探测耗时（毫秒）。
 * 已保存资产与未保存草稿的测试共用此结构。
 */
public record ModelTestResult(String status, long latencyMs) {
}
