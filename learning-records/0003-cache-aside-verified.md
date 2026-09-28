# Cache Aside 缓存回填与失效已完成验证

学员已通过 Swagger、Redis `MONITOR` 和 TTL 验证骑手详情缓存：查询先执行 `GET rider:detail:1`，未命中后执行带 `EX 600` 的 `SET`；更新骑手资料后删除旧 Key。后续可进入 Redis 故障降级和缓存三大问题，而不需要重讲基础 Cache Aside 流程。
