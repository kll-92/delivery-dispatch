# 学习记录：Actuator 观察应用

## 本次掌握

- `spring-boot-starter-actuator` 提供 Spring Boot 管理端点，不是新的业务 Controller 或 HTTP 服务器。
- `management.endpoints.web.exposure.include` 控制哪些管理端点通过 HTTP 暴露；当前是 `health,info,metrics`。
- `health` 看健康状态，`info` 看应用信息，`metrics` 看当前记录的指标名称与数值。
- 修改 `application.yml` 后必须重启正在运行的 Spring Boot JVM；不需要删除 Docker 的 MySQL/Redis 容器。
- Actuator 负责观察现象，不等于压测报告或 JVM 自动调优。

## 待复述

解释为什么旧的 8080 进程可能看不到新加入的 `metrics`，以及在看到指标后还需要哪些证据才能谈性能。
