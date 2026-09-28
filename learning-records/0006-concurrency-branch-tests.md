# 学习记录：并发保护的单元测试

## 本次掌握

- 单元测试通过 Mock 隔离真实 MySQL/Redis，稳定模拟“条件更新影响 0 行”的竞态结果。
- `@Mock` 是假的依赖，`@InjectMocks` 创建被测试 Service，`when(...).thenReturn(...)` 规定 Mock 的返回值。
- `assertThrows` 验证某个失败场景必须抛出预期异常。
- `verify` 验证 Service 调用了正确的 Mapper；`verifyNoMoreInteractions` 验证失败后没有继续写库。
- 这些测试证明业务分支处理正确，不证明真实数据库锁、Redis 联调或性能指标。

## 待复述

区分“第一次查询返回 null”和“第一次查询成功但条件更新返回 0”分别表示什么业务情况。
