# Java 后端项目学习资源

## Knowledge

- [Spring Framework: Declarative Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
  官方文档；用于理解 `@Transactional` 的事务边界、提交和回滚。
- [Spring Framework: Exception Handling](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
  官方文档；用于理解 `@ExceptionHandler` 和 `@RestControllerAdvice` 的全局异常处理。
- [Redis: Data Types](https://redis.io/docs/latest/develop/data-types/)
  Redis 官方文档；用于理解 String、Hash、ZSet、GEO 等数据结构与场景。
- [黑马程序员 Redis 入门到实战](https://www.bilibili.com/video/BV1cr4y1671t/)
  适合 Java 后端学习；当本项目进入缓存、分布式锁和缓存三大问题时按需观看。
- [MySQL 8.4 Reference Manual: Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
  MySQL 官方文档；用于解释普通查询后再更新为何会有并发窗口，以及 InnoDB 如何协调行更新。
- [MySQL 8.4 Reference Manual: InnoDB Locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html)
  MySQL 官方文档；用于理解本项目条件 UPDATE 背后的记录锁与并发更新基础。
- [MyBatis-Plus: 条件构造器](https://baomidou.com/guides/wrapper/)
  官方文档；用于理解 `LambdaUpdateWrapper` 的 `eq`、`set` 和通过 Wrapper 指定局部更新字段。
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
  JUnit 官方文档；用于理解 `@Test`、断言和 `assertThrows` 等测试写法。
- [Spring Boot Actuator: Enabling Production-ready Features](https://docs.spring.io/spring-boot/reference/actuator/enabling.html)
  Spring Boot 官方文档；用于理解 starter、管理端点和 HTTP 暴露配置。
- [Spring Boot Actuator Metrics API](https://docs.spring.io/spring-boot/docs/4.0.x/actuator-api/htmlsingle/)
  Spring Boot 官方文档；用于理解 `/actuator/metrics` 的指标查询方式与边界。
- [Java Virtual Machine Specification, Java SE 21](https://docs.oracle.com/javase/specs/jvms/se21/html/)
  Oracle 官方规范；用于理解 JVM 执行 Java 字节码的基本职责。
- [Java Garbage Collection Tuning Guide](https://docs.oracle.com/en/java/javase/21/gctuning/introduction-garbage-collection-tuning.html)
  Oracle 官方文档；用于后续学习堆、垃圾回收和 GC 观察指标。

## Wisdom (Communities)

- 暂不设置社区任务；当前优先完成项目与模拟面试。
