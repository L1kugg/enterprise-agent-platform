#!/usr/bin/env bash
# 本地开发启动脚本
# 数据库和模型密钥配置在 config/application.yml（Spring 自动加载，不依赖环境变量）
# dev 模式已自动关闭：安全鉴权 / Redis / RabbitMQ / pgvector（向量存内存、入库走数据库轮询）
mvn spring-boot:run
