# 学习记录：JVM 与配送项目

## 本次掌握

- JVM 是运行 Java 字节码的程序，不是一台额外的电脑。
- JVM 负责类加载、字节码执行、内存管理、垃圾回收、线程运行和运行时优化。
- 项目的 `pom.xml` 使用 Java 21；IDEA 启动命令里的 `bin/java` 就是在启动 JVM。
- Spring Boot、内嵌 Tomcat、Controller、Service、Mapper 在同一个 Java/JVM 进程中运行。
- MySQL、Redis 是独立服务；JVM 通过网络连接它们，但它们不属于同一个 JVM。
- 项目目前使用 JVM 默认配置；Actuator 可以观察 JVM 指标，但还没有做专项 JVM 调优。

## 面试表达

“服务运行在 Java 21 JVM 上，JVM 负责字节码执行、内存管理、垃圾回收和线程运行。项目当前使用默认 JVM 配置，并通过 Actuator 观察内存和 GC 指标。”

## 待复述

说明 Spring Boot、Tomcat、JVM、Redis 的关系，并解释 Redis 容器停止时为什么 Java 进程不一定立即消失。
