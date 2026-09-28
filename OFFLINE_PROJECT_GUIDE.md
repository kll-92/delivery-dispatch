# 同城配送智能调度平台：离线总指导书

> 目标：在不做前端、支付、登录和微服务的前提下，完成一个能演示、能讲清楚、能写进 Java 后端实习简历的配送调度项目。
>
> 使用方式：先看当前状态提醒，再按 `LEARNING_ROADMAP.md` 推进。前半部分是从零搭建时的历史教程，已完成部分用于复习，不要重复照着建；涉及当前 API/Key/类名时，以当前代码和 `PROJECT_KNOWLEDGE_REVIEW.md` 为准。

> **当前项目状态提醒（2026-09-27）：**本指导书保留了从零学习 Redis Stream 的历史课程，但实际运行代码已迁移为 **Transactional Outbox + RocketMQ**。Redis 仍负责 Cache Aside、GEO 和缓存锁。当前运行和验收以 [`README.md`](README.md)、[`LEARNING_ROADMAP.md`](LEARNING_ROADMAP.md) 和 [`PROJECT_KNOWLEDGE_REVIEW.md`](PROJECT_KNOWLEDGE_REVIEW.md) 为准；不要把后文 Stream 历史章节当成当前架构。

---

# 离线先修课：遇到陌生词时先看这里

> 这一节不是可跳过的术语表。你后面每看到一个陌生的类、注解、方法或技术名，都回到这里找它的“是什么、为什么、写在哪里、不写会怎样”。

## A. 先建立一张项目全景图

一次“创建订单并派单”的请求，会经过下面这些角色：

```text
Swagger / 浏览器
      │ 发送 HTTP 请求和 JSON
      ▼
Controller（接住请求、从 URL/JSON 取参数）
      ▼
Service（业务规则：能不能派、选谁、状态怎么变）
      ▼
Mapper（把 Java 方法翻译成 SQL）
      ▼
MySQL（长期、最终正确的订单和骑手数据）
      ▲
Redis（高频查询的加速层、位置索引、原子计数）
```

可以把它想成一家配送平台：

| 程序角色 | 生活中的角色 | 主要职责 |
| --- | --- | --- |
| Controller | 前台 | 接收请求、返回结果；不决定复杂业务 |
| Service | 调度员 | 判断订单能否派、骑手能否接、状态如何流转 |
| Mapper | 翻译员 | 把 Java 调用转成 SQL，和数据库沟通 |
| MySQL | 正式档案室 | 订单、手机号、接单数等最终事实 |
| Redis | 高速白板 | 热点详情、实时位置、临时计数；快但不能当唯一档案 |
| Redis Stream | 可保留、可确认的消息流 | 异步排队处理订单派单事件 |
| Outbox 表 | MySQL 中的待寄发件托盘 | 与订单同事务保存待发送事件，Redis 暂不可用时可重试 |

**关键边界：** Controller 不写派单规则；Redis 不保存唯一订单事实；Service 不直接拼大量 SQL；Mapper 不决定业务是否允许。

---

## B. Java 代码阅读基础：你会反复遇到的写法

### B.1 类、字段、方法

```java
public class RiderService {
    private final RiderMapper riderMapper;

    public Rider getById(Long id) {
        return riderMapper.selectById(id);
    }
}
```

逐行读：

| 代码 | 白话 |
| --- | --- |
| `class RiderService` | 定义一个“骑手业务处理器”类型。 |
| `private` | 只能在这个类内部直接使用，外部不能乱改。 |
| `final` | 引用一旦创建就不能换成另一个 Mapper；它不是说数据库数据永远不能变。 |
| `RiderMapper riderMapper` | 一个字段，保存“访问 rider 表的工具”。 |
| `public` | Controller 等其他类可以调用该方法。 |
| `Rider` | 返回值类型，代表返回一位骑手对象。 |
| `Long id` | 入参；`Long` 能表示数据库的 `BIGINT`，还可以是 `null`。 |
| `return` | 把结果交还给调用者，方法到此结束。 |

### B.2 `null`、包装类型与 `BigDecimal`

| 类型 | 用在本项目 | 为什么不用基本类型或 `double` |
| --- | --- | --- |
| `Long` | id、riderId | 数据库值可能尚未生成，`null` 有意义。 |
| `Integer` | status、currentOrderCount | 同理，可区分“0”和“没有值”。 |
| `String` | 姓名、手机号、订单号 | 文本。 |
| `BigDecimal` | 经度、纬度、配送费 | 小数精度稳定；金额绝不能用 `double` 做精确比较。 |
| `List<Rider>` | 骑手列表 | 一个有顺序的 Rider 集合。 |

`null` 不等于 `0`、空字符串或空列表。调用 `null` 的方法会发生 `NullPointerException`，所以写业务代码前要判断“这个对象可能不存在吗”。

### B.3 `if`、异常和早返回

```java
if (!StringUtils.hasText(request.getPhone())) {
    throw new IllegalArgumentException("骑手手机号不能为空");
}
```

- `if`：如果条件成立，执行大括号内的代码。
- `!`：取反；`!hasText` 即“没有有效文本”。
- `throw`：立即停止当前业务流程，并把错误交给全局异常处理器。
- `IllegalArgumentException`：表示调用者传入的业务参数不合法；当前项目会转成 HTTP 400。

为什么不用 `return null`：Controller 最后可能把 `null` 当作正常结果，用户不知道具体哪里错；抛清晰业务异常更适合接口项目。

### B.4 `try/catch`：处理可预期的失败

```java
try {
    return objectMapper.readValue(cacheJson, Rider.class);
} catch (JsonProcessingException e) {
    log.warn("缓存 JSON 解析失败", e);
}
```

`try` 放可能失败的代码；`catch` 决定失败后怎么办。缓存 JSON 坏掉时，正确处理不是让整个接口崩掉，而是记录日志、删掉坏缓存、再去 MySQL 查询。

### B.5 `static final` 常量

```java
private static final String RIDER_DETAIL_KEY_PREFIX = "rider:detail:";
```

- `static`：属于整个类，所有 `RiderService` 对象共用一份。
- `final`：这个字符串引用不能再改。
- 常量名全大写：Java 的约定，告诉别人它不是会变化的普通变量。

它用来统一 Redis Key 前缀，避免某处写 `rider:detail:`、另一处误写成 `rider-detail:`。

---

## C. Spring Boot：为什么代码没有 `new RiderService()`

### C.1 注解是什么

注解是写在类、方法、字段旁边的“说明标签”。Spring Boot 启动时扫描这些标签，自动创建对象和路由请求。

| 注解 | 是什么 | 写在哪里 | 不写会怎样 |
| --- | --- | --- | --- |
| `@RestController` | 这是 HTTP 接口类，返回值自动转 JSON | Controller 类上 | URL 请求找不到对应接口。 |
| `@RequestMapping` | 统一 URL 前缀 | Controller 类/方法 | 接口路径不清晰或不存在。 |
| `@GetMapping` / `@PostMapping` / `@PutMapping` / `@PatchMapping` | HTTP 方法与 URL 的映射 | Controller 方法上 | Spring 不知道何时调用该方法。 |
| `@RequestBody` | 把 JSON 请求体转为 Java 对象 | Controller 参数前 | JSON 字段不会自动装进对象。 |
| `@PathVariable` | 从 URL 中取变量，如 `/riders/1` 的 `1` | Controller 参数前 | 取不到对应 ID。 |
| `@Service` | 这是业务层对象，交给 Spring 管理 | Service 类上 | Spring 无法注入并调用它。 |
| `@Transactional` | 数据库操作组成一个事务 | 多表更新的 Service 方法上 | 一半成功一半失败，数据可能不一致。 |

### C.2 依赖注入与 `@RequiredArgsConstructor`

```java
@Service
@RequiredArgsConstructor
public class RiderService {
    private final RiderMapper riderMapper;
}
```

Spring 启动时会：

```text
先创建 RiderMapper
     ↓
再调用 RiderService 的构造方法，把 RiderMapper 传进去
     ↓
Controller 再拿到 RiderService
```

`@RequiredArgsConstructor` 是 Lombok 帮你为所有 `final` 字段生成构造方法，等价于手写：

```java
public RiderService(RiderMapper riderMapper) {
    this.riderMapper = riderMapper;
}
```

为什么偏好 `final`：依赖必须在对象创建时就准备好，之后不允许被意外替换；代码更稳定、测试更清楚。

### C.3 Entity、DTO、Controller 参数的区别

| 名称 | 是什么 | 本项目例子 |
| --- | --- | --- |
| Entity（实体） | 对应数据库表的一行 | `Rider`、`DeliveryOrder` |
| DTO（数据传输对象） | 专门定义接口输入或输出字段 | `RiderLocationRequest`、`NearbyRiderResponse` |
| Mapper | 访问数据库的接口 | `RiderMapper` |

前期 CRUD 直接把 `Rider` 当请求体可以提速；位置更新只需经纬度时，要用 DTO，避免客户端偷偷传入 `status` 或 `currentOrderCount` 这类不该由它修改的字段。

---

## D. MyBatis-Plus、SQL 与数据库基础

### D.1 Mapper 和 `BaseMapper`

```java
public interface RiderMapper extends BaseMapper<Rider> {
}
```

`BaseMapper<Rider>` 已经提供常用 CRUD：

| 方法 | 相当于什么 SQL |
| --- | --- |
| `selectById(id)` | `SELECT ... WHERE id = ?` |
| `insert(rider)` | `INSERT INTO rider ...` |
| `updateById(rider)` | `UPDATE rider ... WHERE id = ?` |
| `selectCount(wrapper)` | `SELECT COUNT(*) ...` |

它方便的是重复 CRUD；当需要“只有未满载才自增”这种并发条件时，要自己在 Mapper 定义专用 SQL。

### D.2 查询条件 `Wrappers.lambdaQuery()`

```java
Wrappers.<Rider>lambdaQuery()
    .eq(Rider::getPhone, request.getPhone())
    .ne(Rider::getId, id)
```

含义：查找手机号相同、但 id 不等于当前骑手的记录。

- `eq` = equal，等于。
- `ne` = not equal，不等于。
- `Rider::getPhone`：不是调用 getter，而是安全地指向字段；改字段名字时 IDE 更容易同步发现问题。

### D.3 数据库约束与 Java 校验为什么都要有

```text
Java 校验：给用户清晰错误信息，提前拦住大部分错误
MySQL UNIQUE：最后一道底线，挡住并发请求或绕过接口的写入
```

两个同时存在不重复：例如两个人同时注册同一个手机号，二人都可能在 Java 检查时发现“未存在”，但最终数据库唯一索引只会让一个写入成功。

### D.4 索引是什么

索引相当于书的目录。没有索引，数据库可能要一页页翻；有索引可更快定位。

| 本项目索引 | 为何需要 |
| --- | --- |
| `uk_rider_phone` | 手机号唯一，也支持按手机号检查重复。 |
| `idx_rider_status_order_count` | 基础派单按状态、接单数查骑手。 |
| `idx_order_status_created_at` | 查待派单或最近订单。 |

索引不是越多越好：每次写数据也要维护索引，过多会拖慢写入、浪费空间。

---

## E. 事务：不是“有了就绝对安全”

### E.1 `@Transactional` 做了什么

派单会改两张表：订单的 `rider_id/status`，以及骑手的 `current_order_count`。事务让它们：

```text
全部成功 → COMMIT（提交）
任意异常 → ROLLBACK（都撤销）
```

没有事务会出现：订单显示已派给骑手，但骑手接单数没有增加；以后容量统计就错了。

### E.2 它不能解决什么

- 不能自动防止两个线程都读到同一个旧接单数。
- 不能把 MySQL 和 Redis 自动变成一个共同事务。
- 默认只对未捕获的运行时异常回滚；异常被吞掉后可能仍提交。

当前实现用 MySQL 条件更新解决并发占位；缓存删除失败由 TTL/后续读取修复，订单事件跨 Redis 的发布可靠性使用 Outbox，而不是依赖 `@Transactional` 同时提交 MySQL 与 Redis。

---

## F. Docker：你现在实际在用什么

Docker 可以理解为“带好运行环境的独立小盒子”。你电脑上运行 Java，MySQL 与 Redis 各在独立容器里：

```text
Java（Mac） ── localhost:3307 ──> MySQL 容器内部 3306
Java（Mac） ── localhost:6380 ──> Redis 容器内部 6379
```

| 命令 | 意义 |
| --- | --- |
| `docker ps` | 看运行中的容器。 |
| `docker logs delivery-redis` | 看 Redis 容器日志。 |
| `docker exec -it delivery-redis redis-cli` | 进入 Redis 命令行。 |
| `docker pause / unpause` | 临时暂停/恢复，用于演示故障降级。 |

删容器不是关服务。容器如果没有数据卷，删除后数据可能消失；因此不要为了端口错误随意点击 Docker Desktop 的垃圾桶。

---

## G. Redis：从“快”到“为什么正确”

### G.1 Redis 与 MySQL 的一句话区分

```text
MySQL：长期、可恢复、业务最终事实
Redis：内存中快、用于高频读写和临时协调
```

### G.2 `StringRedisTemplate` 是什么

它是 Spring 给 Java 的 Redis String 操作工具。可以把它理解为 Java 版的 `redis-cli`：

| Java 写法 | Redis 命令 |
| --- | --- |
| `opsForValue().get(key)` | `GET key` |
| `opsForValue().set(key, value, ttl, unit)` | `SET key value EX ...` |
| `delete(key)` | `DEL key` |
| `opsForGeo()` | GEOADD、GEOSEARCH 等地理操作 |

### G.3 `ObjectMapper` 为什么要出现

Redis String 存的是字符串；Java 业务使用的是 `Rider` 对象。`ObjectMapper` 负责两次翻译：

```text
Rider 对象 ── writeValueAsString ──> JSON 字符串 ──> Redis
Redis JSON ── readValue(..., Rider.class) ──> Rider 对象
```

### G.4 Cache Aside（缓存旁路）

```text
读：先 Redis → 未命中查 MySQL → 写 Redis → 返回
写：先更新 MySQL → 删除 Redis
```

为什么不直接“更新 MySQL 后马上改 Redis”：需要维护更多分支，缓存写入又可能失败；删旧缓存后由下一次读取自然回填，逻辑简单且常用。

### G.5 TTL 与缓存的三个状态

```text
TTL > 0：缓存存在，数字是剩余秒数
TTL = -1：缓存存在但没有过期时间（通常是危险信号）
TTL = -2：Key 不存在（正常未命中或已被删除）
```

### G.6 Redis GEO 是什么

GEO 是 Redis 的空间索引能力：把骑手 ID 与经纬度保存到同一个 Key，按一个中心点和半径快速找成员。

它回答的是“谁在 3km 内”，不回答“谁一定能接单”。最终能否接单仍要回 MySQL 查询状态和容量。

---

## H. 并发：为什么“看起来没错”的代码会错

### H.1 什么是并发

两个 HTTP 请求几乎同一时刻到达，服务器可能让不同线程同时执行它们。它们不是严格排队的。

### H.2 竞态条件

骑手容量为 3、当前为 2：

```text
请求 A 读到 2，认为可接
请求 B 也读到 2，认为可接
请求 A 写 3
请求 B 也写 3，但实际已经分配了两笔新订单
```

数据库表面上的接单数是 3，实际订单却多了两单；这就是竞态条件。

### H.3 原子操作

原子表示“中间不能被插队”。本项目会用两种：

| 方法 | 谁保证原子 | 用途 |
| --- | --- | --- |
| 条件 `UPDATE` | MySQL | 最终占位、最终正确性。 |
| Redis Lua | Redis | 高频计数判断和自增不被插队。 |

`synchronized` 只锁住当前 Java 进程；如果未来有两台应用机器，它们互相不知道对方的锁，所以不能作为分布式配送系统的最终方案。

---

## I. Redis Stream + Outbox：异步派单如何可靠传递

创建订单时不在 HTTP 请求里同步完成派单。订单与 Outbox 事件先在同一个 MySQL 事务中提交；后台发布器再把事件写进 Redis Stream，消费者异步查附近骑手并派单。

```text
POST 创建订单
  → MySQL 事务写订单 + Outbox(status=0)
  → 定时发布器 XADD 到 Redis Stream
  → 消费者组读取、执行业务
  → 成功后 XACK；失败则保留 Pending 供恢复
```

| 词 | 白话 |
| --- | --- |
| Stream | Redis 中按 ID 保存的消息流；本项目 Key 为 `stream:dispatch:events`。 |
| Consumer Group | 让一组消费者协调处理同一个 Stream。 |
| Pending | 消费者已收到、尚未 ACK 的消息记录。 |
| ACK | 消费者明确告诉 Stream“这条业务处理成功了”。 |
| Outbox | 与订单同事务落库的待投递事件；发布成功后标记已投递。 |
| 至少一次投递 | 为了恢复消息可能重复投递，因此消费者必须幂等。 |
| 幂等 | 同一条消息处理一遍或十遍，最终业务结果都相同。 |

### 为什么不用“提交订单后直接 XADD”

```text
先提交订单 → Redis 暂时不可用 → 订单存在但派单事件可能丢失
先 XADD → MySQL 事务回滚 → Stream 里出现幽灵订单事件
Transactional Outbox → 订单和待发事件同事务提交，发布器失败后可重试
```

Outbox 解决数据库与消息系统之间的双写窗口，但不保证只投递一次：XADD 成功、更新 Outbox 状态前若进程故障，重试可能重复写消息；项目用订单状态校验实现消费幂等。当前不使用 RabbitMQ，也不使用 `AFTER_COMMIT` 监听器。

---

## J. JVM：Java 程序为什么会慢或崩

JVM 是运行 Java 程序的虚拟机。不是每个问题都叫“内存溢出”，先区分：

| 现象 | 第一怀疑方向 |
| --- | --- |
| 接口偶发变慢 | 慢 SQL、Redis/MQ 连接、线程阻塞、GC pause |
| CPU 很高 | 死循环、频繁序列化、过多线程、GC |
| 内存持续上涨 | 对象泄漏、缓存无 TTL、集合不断累积 |
| 请求卡住 | 外部依赖超时、线程池耗尽、锁竞争 |

| JVM 区域 | 保存什么 |
| --- | --- |
| Heap 堆 | 大部分 Java 对象；GC 主要回收这里。 |
| Stack 栈 | 每个线程的方法调用和局部变量。 |
| Metaspace 元空间 | 类的信息。 |

排查顺序永远是：先看指标 → 再抓线程/GC 证据 → 最后调整参数。没有证据不要盲目调大 `-Xmx`。

---

## K. 每个任务的离线自检模板

每完成一小步，写下这五句话；写不出来就不要急着进入下一步：

```text
1. 它解决的真实问题是什么？
2. 最终数据以 MySQL 还是 Redis 为准？
3. 我新增/修改的方法入参和返回值是什么？
4. 失败时会怎样，如何验证？
5. 面试官问“为什么这样做”时，我怎么回答？
```

---

# 第一部分：已经完成的内容，从零复盘一遍

> 这一部分不是历史记录，而是你以后面试前必须能重新讲出来的“已实现功能说明书”。后续的 Redis、GEO、MQ 都建立在这些基础上。

## 1. 项目为什么能启动：Spring Boot、Maven、Tomcat

### 1.1 `pom.xml` 是什么

`pom.xml` 是 Maven 的项目清单。它告诉 Maven：项目叫什么、使用什么 Java 版本、需要下载哪些第三方库。

| 依赖 | 在项目中干什么 | 如果没有它 |
| --- | --- | --- |
| `spring-boot-starter-web` | Controller、HTTP、内嵌 Tomcat、JSON | 不能提供 Web 接口。 |
| `mybatis-plus-spring-boot3-starter` | Mapper 与 MySQL CRUD | 不能方便操作数据库。 |
| `mysql-connector-j` | Java 连接 MySQL 的驱动 | 代码不知道如何和 MySQL 通信。 |
| `spring-boot-starter-data-redis` | `StringRedisTemplate`、Redis 客户端 | 不能方便访问 Redis。 |
| `springdoc-openapi...` | 自动生成 Swagger 文档页 | 需要手写接口测试。 |
| `spring-boot-starter-actuator` | 健康检查和指标 | 很难快速判断服务是否健康。 |
| Lombok | 自动生成 getter、setter、构造器 | 要手写大量重复代码。 |

`mvn -q -DskipTests compile` 的意思：让 Maven 编译主代码，但暂时不执行测试。它是每次修改后最基础的“语法和依赖检查”。

### 1.2 启动类做什么

文件：`DeliveryDispatchApplication.java`。

里面的 `@SpringBootApplication` 可以理解为总开关：启动 Spring、扫描当前包及子包的 `@Controller`、`@Service`、`@Mapper` 等组件，并启动内嵌 Tomcat。

### 1.3 Tomcat、Swagger 不是同一个东西

| 名称 | 它是什么 | 在你的项目里干什么 |
| --- | --- | --- |
| Tomcat | HTTP Web 服务器 | 真正监听 `8080` 端口，接收浏览器/Swagger 的请求。 |
| Swagger | 接口文档和测试页面 | 帮你点按钮发 HTTP 请求；它自己不处理派单业务。 |

关系是：

```text
Swagger 页面 ──请求──> Tomcat ──> Controller ──> Service
```

所以网页能打开 Swagger，不代表数据库或 Redis 一定正常；真正业务是否成功要看接口返回和日志。

---

## 2. 配置与环境变量：为什么密码不写死

文件：`src/main/resources/application.yml`。

它保存应用配置，例如：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3307/delivery_dispatch...}
    username: ${DB_USERNAME:delivery_user}
    password: ${DB_PASSWORD}
```

`${DB_PASSWORD}` 的意思：运行时从 IDEA Run Configuration 或系统环境变量里读取密码。

为什么不能直接写：

```yaml
password: 真实密码
```

因为代码会上传 Git、发给同学或展示给面试官；密码泄露后别人可直接连接数据库。`.env.example` 可以写变量名字和示例，但不要提交真实值。

`server.port: 8080` 指的是**你的整台电脑上**这个 Spring Boot 进程要占用的端口，不是某一个 Java 文件夹独享的端口。若有另一个程序已监听 8080，新项目启动就会报端口占用。

---

## 3. Docker、MySQL 和端口映射

### 3.1 为什么数据库放 Docker

你没有把 MySQL 安装进 Mac 系统，而是让 MySQL 在 `delivery-mysql` 容器里运行。它和其他项目更隔离，删掉这个容器不会影响你电脑上可能存在的其他数据库。

### 3.2 `3307:3306` 怎么读

```text
Java 访问 localhost:3307
       ↓ Docker 转发
MySQL 容器内部的 3306
```

左边是电脑端口，右边是容器内部端口。MySQL 默认内部端口是 3306；这里选 3307 是为了避免冲突。

同样：`6380:6379` 表示 Java 访问 `localhost:6380`，Docker 转给 Redis 容器内部默认 6379。

### 3.3 在 Docker Desktop 看什么

| 容器 | 用途 | 正常状态 |
| --- | --- | --- |
| `delivery-mysql` | 存订单、骑手等长期数据 | Running，端口 `3307:3306`。 |
| `delivery-redis` | 缓存、GEO、短期高频状态 | Running，端口 `6380:6379`。 |
| Redis Stream | 异步派单事件 | 存在 `delivery-redis` 容器中的 `stream:dispatch:events`。 |

Docker Desktop 的 Stop 只是停止容器；Delete 会删除容器。删除前先确认是否有数据卷和是否真不需要它。

---

## 4. 数据库建模：骑手表和订单表为什么这样设计

### 4.1 `rider` 表：一位骑手的一行档案

| 列 | 类型 | 含义 | 为什么这样设计 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | 数据库主键 | 自增、内部稳定 ID。 |
| `rider_no` | `VARCHAR(32)` | 业务骑手编号，如 R1001 | 可展示、可人工识别；必须唯一。 |
| `name` / `phone` | `VARCHAR` | 骑手资料 | 手机号必须唯一，姓名可重复。 |
| `status` | `TINYINT` | 1 可接单、3 已停用 | 数字占空间少，Java 用枚举解释。 |
| `longitude` / `latitude` | `DECIMAL(10,7)` | 最后一次坐标 | 精度足够，避免浮点误差。 |
| `current_order_count` | `INT` | 当前进行中订单数 | 派单和完成时变化。 |
| `created_at` / `updated_at` | `DATETIME` | 审计时间 | 追溯何时创建、最后何时更新。 |

### 4.2 四种关键数据库规则

```sql
PRIMARY KEY (id)
UNIQUE KEY uk_rider_no(rider_no)
UNIQUE KEY uk_rider_phone(phone)
KEY idx_rider_status_order_count(status, current_order_count)
```

| 规则 | 白话 | 例子 |
| --- | --- | --- |
| 主键 `PRIMARY KEY` | 每行唯一身份证 | `id=1` 只对应一位骑手。 |
| 唯一 `UNIQUE` | 绝不允许重复 | 两人不能都叫 `R1001`；手机号也不能重复。 |
| 普通索引 `KEY` | 查询目录 | 派单时按状态和接单数找骑手更快。 |
| 默认值 `DEFAULT` | 没传值时数据库自己补 | 新骑手初始接单数默认 0。 |

### 4.3 `delivery_order` 表：一个订单的三方关系

```text
商家（取货点） ──> 骑手（执行配送） ──> 收货人（目的地）
```

| 字段组 | 为什么存在 |
| --- | --- |
| `merchant_*` | 商家名称、电话、地址、坐标；骑手到店取货。 |
| `receiver_*` | 收货人名称、电话、地址、坐标；骑手完成配送。 |
| `rider_id` | 派单后关联骑手；待派单时可以为 `null`。 |
| `status` | 订单当前在哪个阶段。 |
| `delivery_fee` | 配送费，使用 `BigDecimal`。 |
| `order_no` | 面向业务展示的订单号，必须唯一。 |

“取货联系人”不是骑手。现实中骑手到商家取货，联系人应是商家；订单中的收货联系人是收货人。

### 4.4 一对多关系

一位骑手在不同时刻可以完成很多订单；一个订单在当前项目中最多分配给一名骑手：

```text
Rider 1 ── 0..N DeliveryOrder
DeliveryOrder ── 0..1 Rider
```

因此 `DeliveryOrder` 放 `riderId`，而不是在 `Rider` 表存一个 `orderId`。

---

## 5. 实体类：Java 如何认识数据库的一行数据

文件：`rider/entity/Rider.java` 和 `order/entity/DeliveryOrder.java`。

```java
@Data
@TableName("rider")
public class Rider {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
}
```

| 写法 | 是什么 | 不写会怎样 |
| --- | --- | --- |
| `@Data` | Lombok 自动生成 getter、setter、`toString` 等 | 你得为每个字段手写读写方法。 |
| `@TableName("rider")` | 告诉 MyBatis-Plus 类对应 `rider` 表 | 表名和类名对不上时，SQL 找错表。 |
| `@TableId(... AUTO)` | `id` 是数据库自增主键 | 插入后主键回填可能不正确。 |
| `private Long id` | Java 中保存 BIGINT 的字段 | 用 `int` 可能不够大，也不能表达未生成 ID。 |

数据库列是下划线 `current_order_count`，Java 字段是驼峰 `currentOrderCount`。MyBatis-Plus 会按常见规则转换；这叫“下划线转驼峰映射”。

---

## 6. RiderMapper：为什么只写这么少

文件：`rider/mapper/RiderMapper.java`。

```java
public interface RiderMapper extends BaseMapper<Rider> {
}
```

它是接口，不是普通类；你不需要自己 `new RiderMapper()`。MyBatis-Plus 在启动时为这个接口生成实现，`BaseMapper` 也已经带好基本 CRUD。

这就是为什么 Service 里能直接写：

```java
riderMapper.selectById(id);
riderMapper.insert(rider);
riderMapper.updateById(rider);
```

后面涉及并发条件更新时，才在 Mapper 里补一两个专用方法，而不是把所有 SQL 都手写。

---

## 7. 已完成：创建骑手 `POST /api/v1/riders`

### 7.1 请求怎么进来

Controller 方法大意：

```java
@PostMapping
public Rider create(@RequestBody Rider rider) {
    return riderService.create(rider);
}
```

`POST` 常用于“创建资源”。Swagger 填入 JSON 后，Spring 通过 `@RequestBody` 把 JSON 变为 `Rider` 对象；然后 Controller 把对象交给 Service。

### 7.2 Service 做了哪些业务判断

```text
编号非空
→ 姓名非空
→ 手机号非空
→ rider_no 是否已经存在
→ phone 是否已经存在
→ 设置可接单状态和当前接单数 0
→ INSERT 到 MySQL
→ 返回新骑手（其中 id 已由数据库生成）
```

为什么名称允许重复：现实中可以有多个“张三”；为什么编号和手机号不允许重复：它们是识别骑手的业务标识。

`StringUtils.hasText` 不只判断 `null`，还会把 `""` 和只有空格的字符串当作无效，因此比 `value == null` 更适合接收用户文本。

### 7.3 Java 重复校验和数据库唯一索引的关系

Service 里的 `selectCount` 是为了返回“手机号已存在”这类易懂提示；数据库里的 `UNIQUE` 是最终防线。两个并发创建请求可能同时查到 0，但数据库最终只能允许一个插入成功。

### 7.4 Swagger 验证数据

```json
{
  "riderNo": "R1002",
  "name": "李四",
  "phone": "13800000002",
  "longitude": 116.400000,
  "latitude": 39.917000
}
```

预期：响应有 `id`、`status: 1`、`currentOrderCount: 0`。

---

## 8. 已完成：查询、列表、更新、停用骑手

### 8.1 按 ID 查询：`GET /api/v1/riders/{id}`

`/riders/1` 中的 `1` 不是普通文本，而是路径变量；`@PathVariable Long id` 把它取出来。

读取链路目前已经升级为：

```text
先读 Redis rider:detail:1
→ 命中：JSON 还原 Rider，直接返回
→ 未命中：MySQL selectById
→ 查到：写 Redis，TTL 约 600～660 秒随机值，返回
→ 查不到：抛“骑手不存在”
```

### 8.2 查询列表：`GET /api/v1/riders`

`List<Rider>` 代表多位骑手，Swagger 显示的 `[` 和 `]` 表示 JSON 数组。它返回全部字段是基础阶段的简化做法；后期真正上线时要加分页，避免上万骑手一次性返回。

### 8.3 更新：`PUT /api/v1/riders/{id}`

`PUT` 表示对一个已存在资源进行整体更新。当前允许改：姓名、手机号、经纬度。

最容易混淆的一条校验：手机号不能和**其他人**重复，但可以保持自己的手机号不变。

```text
错误查询：手机号相同就算重复
正确查询：手机号相同 且 id 不等于当前骑手 id，才算重复
```

MyBatis-Plus 中：

```java
.eq(Rider::getPhone, request.getPhone())
.ne(Rider::getId, id)
```

`ne` 就是 not equal（不等于）。如果不加 `ne`，张三把自己的手机号原样提交，数据库必然查到张三自己，程序会误报“手机号已存在”。

### 8.4 停用：`PATCH /api/v1/riders/{id}/disable`

`PATCH` 表示局部修改。这里不删除骑手，而是把状态改为 `DISABLED`。

为什么不直接 `DELETE`：订单历史仍需关联骑手，物理删除会让历史数据丢失或外键关系断裂。逻辑停用让骑手不能再接新订单，但历史订单仍可追溯。

业务规则：若 `currentOrderCount > 0`，不能停用。因为一个正在配送的骑手被停用会让履约流程失去执行人。

### 8.5 这一部分的当前缓存修正

你已经把查询放进 Redis，但还需要完成“写后删缓存”：更新/停用后删除 `rider:detail:{id}`。这正是本指导书后面“任务 A”的第一步。

---

## 9. 已完成：统一异常处理

文件：`common/exception/GlobalExceptionHandler.java`。

没有统一异常处理时，Service 抛出异常可能会返回很长的英文堆栈，既不安全也不友好。

当前规则：

```text
IllegalArgumentException
→ HTTP 400
→ { "code": 400, "message": "手机号已存在" }

其他未预料异常
→ HTTP 500
→ { "code": 500, "message": "系统异常，请稍后重试" }
```

HTTP 400 表示客户端请求不符合业务规则；HTTP 500 表示服务端自己发生了未预期错误。不要把“骑手不存在”这种业务问题错误地当成 500。

---

## 10. 已完成：创建配送订单

接口：`POST /api/v1/orders`。

### 10.1 创建订单做的事

```text
检查商家名称、电话、地址、经纬度
→ 检查收货人名称、电话、地址、经纬度
→ 检查配送费不为负数
→ 生成订单号 ORD + UUID 片段
→ 状态设为 PENDING_DISPATCH（0）
→ riderId 设为 null
→ 配送费没传则设为 0
→ 插入 MySQL
```

订单刚创建时没有骑手是正确的：它正在等待调度。

`UUID` 是随机且极难重复的标识；本项目把它的一部分拼进 `ORD...`，用于生成展示给业务人员的订单号。数据库的唯一索引仍是最终保证。

### 10.2 创建订单示例

```json
{
  "merchantName": "幸福咖啡店",
  "merchantPhone": "010-12345678",
  "merchantAddress": "北京市东城区示例路 1 号",
  "merchantLongitude": 116.397128,
  "merchantLatitude": 39.916527,
  "receiverName": "王五",
  "receiverPhone": "13900000001",
  "receiverAddress": "北京市东城区示例路 2 号",
  "receiverLongitude": 116.405000,
  "receiverLatitude": 39.920000,
  "deliveryFee": 5.00
}
```

---

## 11. 已完成：订单状态机

### 11.1 状态不是随便写数字

`OrderStatus` 枚举把数字翻译为业务含义：

```text
0 PENDING_DISPATCH  待派单
1 ASSIGNED          已派单
2 DELIVERING        配送中
3 COMPLETED         已完成
4 CANCELED          已取消
```

允许的流转：

```text
待派单 ──派单──> 已派单 ──开始配送──> 配送中 ──完成──> 已完成
   │
   └──取消──> 已取消
```

不允许：已完成再取消、待派单直接完成、配送中换骑手（本项目暂未实现改派）。

### 11.2 为什么 Service 每次先检查状态

例如 `startDelivery`：只有状态为 `ASSIGNED` 才改为 `DELIVERING`。如果没有这个判断，客户端可以直接把未派单订单改成配送中，业务数据会失真。

状态机本质是：把“哪些变化被允许”写进代码，而不是相信前端按钮不会点错。

---

## 12. 已完成：手动派单与基础自动派单

### 12.1 手动派单

接口：`PATCH /api/v1/orders/{orderId}/assign/{riderId}`。

它要同时满足：

```text
订单存在，且仍待派单
骑手存在，且状态为 AVAILABLE
```

成功后同时发生：

```text
订单 riderId = 骑手 ID，状态 0 → 1
骑手 currentOrderCount + 1
```

### 12.2 自动派单（当前基础版）

接口：`PATCH /api/v1/orders/{orderId}/dispatch`。

当前 SQL 思路：从可接单骑手中，按 `currentOrderCount` 升序、`id` 升序选第一人。

这版解决了“没有人工指定骑手时如何分配”，但还没有考虑距离，也还不能抗并发；所以后面会进化为：

```text
Redis GEO 找附近 → MySQL 过滤可用状态/容量
→ 负载和距离排序 → 条件更新原子占位
```

### 12.3 为什么 `assignRider/dispatch/complete` 有 `@Transactional`

它们会同时改订单和骑手。比如 `complete`：订单变完成、骑手接单数减一，必须要么都成功，要么都失败。

### 12.4 一个必须知道的当前局限

基础版用“查到骑手对象 → Java 中 `+1` → `updateById`”。单人测试没有问题，但两个派单请求同时到达时可能超接单。不是你写错语法，而是并发环境下读改写不是原子的；这就是后面“任务 H”要升级的核心原因。

---

## 13. 已完成：订单履约和取消

| 接口 | 允许前状态 | 成功后状态 | 额外动作 |
| --- | --- | --- | --- |
| `start-delivery` | 已派单 | 配送中 | 无 |
| `complete` | 配送中 | 已完成 | 骑手接单数 -1 |
| `cancel` | 待派单 | 已取消 | 不改变骑手接单数，因为尚未派骑手 |

为什么取消只允许待派单：订单一旦已分配、已配送，取消涉及骑手补偿、库存、退款等更复杂业务；本项目先明确限制，避免假装完成了复杂业务。

---

## 14. 已完成：单元测试到底在测什么

文件：`src/test/java/com/kll/deliverydispatch/order/service/DeliveryOrderServiceTest.java`。

单元测试不启动整个网页、也不连接真实 MySQL。它用 Mockito 造出假的 Mapper，只验证 Service 业务规则。

| 测试 | 验证的规则 |
| --- | --- |
| `shouldStartAssignedOrder` | 已派单才能开始配送。 |
| `shouldCompleteOrderAndDecreaseRiderLoad` | 完成订单会把骑手接单数减一。 |
| `shouldRejectCancelOfAssignedOrder` | 已派单订单不能取消。 |

`@Mock` 表示假对象；`when(...).thenReturn(...)` 表示“假设数据库返回这个”；`verify(...)` 表示“确认代码真的调用过更新”。

测试不是为了凑文件数。它使你以后改派单代码时，能快速发现自己有没有破坏原来的状态机规则。

---

## 15. 已完成：Redis 第一课和当前缓存代码

### 15.1 你已经实际操作过的命令

```text
SET rider:detail:2 '{...}'  写值
GET rider:detail:2            读值
EXPIRE rider:detail:2 60      设置 60 秒过期
TTL rider:detail:2            查看剩余秒数
DEL rider:detail:2            删除
```

这五条命令和 Java 中 `StringRedisTemplate` 的对应关系已在先修课 G.2 中说明。

### 15.2 当前 `getById` 方法在做什么

文件：`RiderService.java`。

```text
拼 Key：rider:detail:{id}
→ Redis GET
→ 有 JSON：ObjectMapper 解析为 Rider 后直接 return
→ 没有：MySQL selectById
→ 有数据：ObjectMapper 转 JSON，Redis SET 约 600～660 秒随机 TTL
→ return Rider
```

`ObjectMapper` 是因为 Redis 中存的是字符串，而 Java 中业务代码要用对象。

### 15.3 为什么中文会显示 `\xe6...`

Redis 存的是字节，默认 `redis-cli` 有时会把非 ASCII 字节转义显示；不等于中文损坏。使用：

```bash
docker exec -it delivery-redis redis-cli --raw
```

可以直接看到中文。

### 15.4 当前实现状态

骑手详情 Cache Aside、缓存失效、空值缓存、随机 TTL、缓存重建互斥锁、Redis 故障回源、GEO 位置索引和附近查询都已落入当前代码。之后不要重复实现这些功能；按 `LEARNING_ROADMAP.md` 先做 Outbox/Stream 真实联调，再做并发、JVM 与最终项目复盘。

---

## 16. 已完成内容的面试总述（背自己的项目，不背空话）

> 我完成了同城配送的基础履约闭环：骑手与订单管理、订单状态机、附近候选和自动派单。MySQL 保存最终订单事实；Redis Cache Aside 降低详情读取压力，Redis GEO 提供附近骑手候选，MySQL 条件更新保护订单状态和骑手接单容量。创建订单时使用 Transactional Outbox 把待派单事件与订单一同写入 MySQL，再由发布器投递到 Redis Stream；消费者组异步处理、ACK，并通过 Pending 恢复和订单状态校验处理失败与重复消息。

这段话里每一个点都要能展开回答“为什么”，而不是只报技术名。

---

# 第二部分：当前实现结构与关键方法复盘

> 下面的路径以当前真实代码为准。初学时预想的类名与最终实现可能不同，遇到冲突以 `src/main/java` 下实际文件为准。

## 17. 当前关键目录地图

```text
com.kll.deliverydispatch
├── common
│   └── cache
│       └── RedisLockService.java          # 缓存重建锁、token 校验后 Lua 解锁
├── rider
│   ├── dto
│   │   ├── RiderLocationRequest.java
│   │   └── NearbyRiderResponse.java
│   ├── controller
│   │   └── RiderController.java
│   ├── entity / enums
│   ├── mapper
│   │   └── RiderMapper.java              # tryClaimForOrder / releaseOrderSlot 条件 SQL
│   └── service
│       └── RiderService.java             # CRUD、Cache Aside、位置 GEO、附近候选
├── order
│   ├── entity / enums / event
│   ├── mapper
│   │   ├── DeliveryOrderMapper.java      # assignIfPending / completeIfDelivering
│   │   └── DispatchOutboxMapper.java     # 待投递行锁定、已投递标记
│   └── service
│   │   └── DeliveryOrderService.java     # 订单状态、GEO 调度、并发条件更新
└── order/stream
    ├── DispatchOutboxPublisher.java      # Outbox → Redis Stream
    ├── DispatchStreamConsumer.java       # 消费、ACK、Pending 恢复
    └── DispatchStreamConstants.java
```

## 18. DTO：位置请求和附近骑手响应（已实现）

### 18.1 `RiderLocationRequest`

**是什么：** 客户端只允许提交经纬度的请求对象。

**为什么不用 `Rider`：** `Rider` 里还有手机号、状态、接单数等字段。若接口直接接收整个 `Rider`，客户端理论上可以伪造 `currentOrderCount` 或把自己改为可用状态；DTO 限制了可修改范围。

**文件：** `rider/dto/RiderLocationRequest.java`

**字段：**

```java
private BigDecimal longitude;
private BigDecimal latitude;
```

**不需要：** `id`。因为 ID 来自 URL `/riders/{id}/location`；同一份 ID 不应该既来自 URL 又来自 JSON，避免冲突。

### 18.2 `NearbyRiderResponse`

**是什么：** 返回附近骑手时专用的输出对象。

**字段：**

```java
private Long riderId;
private String riderNo;
private String name;
private Integer currentOrderCount;
private Double distanceKm;
```

**为什么不是直接返回 `Rider`：** 附近搜索需要增加距离 `distanceKm`，而它不是 `rider` 表字段；同时不应该向任何调用者返回手机号等敏感资料。

## 19. Controller 接口职责

> “方法合同”就是提前说清入参、返回值和副作用。写代码前先写合同，思路会清晰很多。

| 接口 | Controller 方法签名概念 | Service 方法应该做什么 | 返回什么 |
| --- | --- | --- | --- |
| 位置上报 | `updateLocation(Long id, RiderLocationRequest request)` | 校验 → 更新 MySQL 坐标 → 更新 GEO → 清理详情缓存 | 最新 `Rider` |
| 附近骑手 | `findNearby(longitude, latitude, radiusKm, limit)` | GEO 查候选 → MySQL 过滤 → 组装距离 | `List<NearbyRiderResponse>` |
| 自动派单 | `dispatch(Long orderId)` | 订单校验 → 附近候选 → 条件占位 → 绑定订单 | 最新 `DeliveryOrder` |
| 创建订单 | `create(DeliveryOrder order)` | 校验并生成订单号 → 同事务插入订单和 Outbox | 新订单 |

Controller 只负责接收参数和调用 Service。经纬度范围、骑手状态、容量、距离排序都属于 Service。

## 20. Mapper 条件更新职责

### 20.1 `RiderMapper.tryClaimForOrder`

建议签名：

```java
int tryClaimForOrder(@Param("riderId") Long riderId,
                     @Param("availableStatus") int availableStatus,
                     @Param("maxOrderCount") int maxOrderCount);
```

**入参：** 目标骑手 ID。

**返回：** SQL 受影响行数，不是骑手对象。

```text
1 = 当前可用且未满载，名额占成功
0 = 不存在 / 已停用 / 已满载，名额没占到
```

SQL 同时检查骑手可用、且 `current_order_count < maxOrderCount`，并在同一条语句里自增。返回 `int` 是因为并发业务最关心“这次有没有真的改到一行”。

### 20.2 `DeliveryOrderMapper.assignIfPending`

当前签名：

```java
int assignIfPending(@Param("orderId") Long orderId,
                    @Param("riderId") Long riderId,
                    @Param("pendingStatus") int pendingStatus,
                    @Param("assignedStatus") int assignedStatus);
```

**规则：** 只有订单仍处于 `PENDING_DISPATCH`（0）时才更新 `rider_id` 和状态。并发两次派同一个订单，只有最先执行成功的一次得到 `1`；另一条 SQL 返回 `0`。

## 21. Service 方法职责

| 方法 | 入参 | 返回值 | 最重要的规则 |
| --- | --- | --- | --- |
| `updateLocation` | riderId、经纬度 DTO | Rider | 骑手存在且可用；先 MySQL、再 GEO、再删缓存。 |
| `findNearby` | 中心点、半径 | 附近骑手列表 | GEO 只是候选，必须回 MySQL 过滤状态和容量。 |
| `dispatch` | orderId | DeliveryOrder | 只派待派单订单；容量由条件更新最终确认。 |
| `dispatchFromStream` | orderId | `void` | 订单不再待派单时直接返回，防止重复事件二次派单。 |
| `publishPendingMessages` | 无 | `void` | 锁定 Outbox 待投递行，XADD 成功后标记已投递；失败保留重试。 |
| `consumeDispatchEvents` | 无 | `void` | 恢复超时 Pending、处理自己的 Pending、读取新消息；成功后 ACK。 |

## 22. 后续每个阶段的“先建什么、后写什么”

### 阶段一：缓存（已实现，回看代码）

```text
1. `getById` 先读 Redis，未命中后读 MySQL 并回填
2. 写 MySQL 成功后删除详情缓存
3. Redis 故障时查询降级回 MySQL
4. 负缓存、随机 TTL 和互斥锁控制热点重建
```

### 阶段二：GEO（已实现，回看代码）

```text
1. 上报位置：校验 → MySQL 持久化 → Redis GEO 更新 → 清理详情缓存
2. 查附近：GEO 生成候选 → MySQL 过滤状态与接单容量 → 返回距离
3. 自动派单按候选顺序尝试原子占位
```

### 阶段三：并发

```text
1. RiderMapper 条件更新：可用且未满载才占用一单
2. DeliveryOrderMapper 条件更新：订单仍待派单才绑定骑手
3. `@Transactional` 把骑手占位与订单更新放在同一个 MySQL 事务
4. 用并发请求验证最多一次成功；Redis Lua 容量闸门是可选扩展，不是当前正确性的依赖
```

### 阶段四：消息队列（当前实现为 Redis Stream + Outbox）

```text
1. 创建订单事务内同时写订单与 Outbox(status=0)
2. 发布器 `FOR UPDATE SKIP LOCKED` 领取待投递记录
3. 发布器 XADD 到 Redis Stream；成功后才更新 Outbox(status=1)
4. 消费者组读取事件并执行附近骑手派单
5. 成功 ACK；失败保持 Pending，超时后 claim 恢复
6. 至少一次投递可能重复，`dispatchFromStream` 用订单状态保证幂等
7. 运行单测后，按路线图完成真实 MySQL + Redis 联调
```

上面是从零搭建时的历史步骤安排。当前核心功能已经实现；请先看本文件开头的状态提醒和 `LEARNING_ROADMAP.md`，不要重复从头搭建。

---

## 0. 先看全局：你要交付什么

### 0.1 最终演示闭环（核心代码已实现，端到端待验收）

```text
创建骑手 → 上报位置 → 创建订单 → 找附近候选骑手
→ 正确且不超量地派单 → 骑手开始配送 → 完成订单
→ Outbox 异步发布订单事件 / Stream Pending 恢复 → 监控与小规模并发验收
```

### 0.2 最终技术亮点

| 能力 | 解决的问题 | 对应技术 |
| --- | --- | --- |
| 骑手详情缓存 | 热点详情反复访问 MySQL | Redis String、Cache Aside、TTL |
| 附近骑手检索 | 快速从经纬度找候选骑手 | Redis GEO |
| 防止超接单 | 并发派单不能超过骑手容量 | MySQL 条件更新 + 事务；Redis Lua 容量闸门未实现 |
| 异步订单事件 | 下单后异步派单、失败后可恢复 | Redis Stream + Transactional Outbox + Pending/ACK |
| 稳定性与排障 | 知道服务是否健康、性能瓶颈在哪里 | Actuator、GC 日志、jcmd、压测 |

本指导书前面的任务 A/B/C 等是从零学习时的历史教程，大部分功能已经写入当前代码。现在请以 `LEARNING_ROADMAP.md` 的完成状态为准，不要再从旧任务列表重复创建功能。

### 0.3 这轮不做的事

- 前端、地图 SDK、真实定位、支付、用户登录、微服务拆分。
- 这些不是做不出来，而是会挤占 Redis、MQ、并发和 JVM 的学习时间；它们对当前 Java 后端实习的投入产出不高。

---

## 1. 当前项目已经有什么

### 1.1 已完成的业务

- Spring Boot + MyBatis-Plus + MySQL + Swagger + Actuator。
- 骑手创建、查询、列表、更新、停用。
- 订单创建、查询、手动派单、基础自动派单、开始配送、完成、取消。
- 订单状态机和基础 `@Transactional` 事务。
- Docker MySQL：`localhost:3307`；Docker Redis：`localhost:6380`。
- Redis String Cache Aside：空值缓存、随机 TTL、更新后失效、故障回源、互斥重建。
- Redis GEO：骑手位置索引、附近候选查询和自动派单候选排序。
- MySQL 条件更新：保护订单单次派单和骑手最大接单数。
- Redis Stream + Outbox：异步派单、ACK、Pending 恢复、订单状态幂等。

### 1.2 现在的重要文件

| 用途 | 文件 |
| --- | --- |
| 骑手缓存、更新、停用 | `src/main/java/com/kll/deliverydispatch/rider/service/RiderService.java` |
| 基础派单与订单履约 | `src/main/java/com/kll/deliverydispatch/order/service/DeliveryOrderService.java` |
| 骑手实体 | `src/main/java/com/kll/deliverydispatch/rider/entity/Rider.java` |
| 订单实体 | `src/main/java/com/kll/deliverydispatch/order/entity/DeliveryOrder.java` |
| 当前表结构 | `src/main/resources/sql/01-schema.sql` |
| Redis 总复习笔记 | `REDIS_STUDY_NOTES.md` |
| 项目进度 | `LEARNING_ROADMAP.md` |

### 1.3 每次开工前的环境检查

在项目根目录运行：

```bash
docker ps
docker exec delivery-redis redis-cli ping
mvn -q -DskipTests compile
```

预期：Redis 返回 `PONG`；编译命令无报错。

启动项目后检查：

```text
http://localhost:8080/actuator/health
http://localhost:8080/actuator/metrics
http://localhost:8080/swagger-ui.html
```

如果端口 `8080` 被占用，先在 IDEA 的 Run 面板停止旧的 `DeliveryDispatchApplication`，而不是随机删除 Docker 容器。

### 1.4 每个新功能的固定学习顺序

1. 先说出它解决的真实问题。
2. 说出数据最终以谁为准：MySQL 还是 Redis。
3. 先写最小业务规则，再写代码。
4. 编译。
5. Swagger / Redis CLI 做一次可见验证。
6. 把“为什么这样做”写进 `REDIS_STUDY_NOTES.md` 或项目复盘。

---

# 第一阶段：把缓存做成正确闭环

预计：2.5～3.5 小时。完成后，你能真正讲 Cache Aside，而不只是背概念。

## 2. 任务 A：读走 Redis，写走 MySQL

### 为什么

`getById` 是读接口，可以先读 Redis。`update`、`disable` 是写接口，绝不能拿可能过期的缓存作为业务判断依据。

危险例子：Redis 还缓存着“骑手当前接单数是 0”，但 MySQL 已经是 1。此时若 `disable()` 先读缓存，就会错误地允许停用正在配送的骑手。

### 目标流程

```text
GET 详情：Redis → 未命中才查 MySQL → 写回 Redis
PUT/PATCH：MySQL 查最新 → 校验 → 更新 MySQL 成功 → DEL Redis 旧缓存
```

### 改哪里

文件：`rider/service/RiderService.java`

### 你要写的最小代码

1. 新建私有方法，强制从数据库取骑手：

```java
private Rider getByIdFromDatabase(Long id) {
    Rider rider = riderMapper.selectById(id);
    if (rider == null) {
        throw new IllegalArgumentException("骑手不存在");
    }
    return rider;
}
```

2. `getById` 缓存未命中后，调用 `getByIdFromDatabase(id)`，不要重复写查询和判空。
3. `update`、`disable` 开头原来的 `getById(id)` 都替换成 `getByIdFromDatabase(id)`。
4. 两个方法的 `riderMapper.updateById(rider)` **后面**都写：

```java
stringRedisTemplate.delete(RIDER_DETAIL_KEY_PREFIX + id);
```

### 为什么是“更新数据库后删缓存”

如果反过来：先删缓存 → 并发请求查到 MySQL 旧值并写回 Redis → 数据库再更新成功，Redis 就会留下旧值直到 TTL 到期。

因此基础顺序固定为：

```text
更新 MySQL 成功 → 删除 Redis
```

### 验证

1. Swagger 先 `GET /api/v1/riders/{id}` 一次，让缓存生成。
2. 终端：

```bash
docker exec delivery-redis redis-cli TTL rider:detail:1
```

看到正数，代表缓存存在。

3. Swagger `PUT /api/v1/riders/{id}` 改名字。
4. 再运行 TTL；预期为 `-2`，因为旧缓存被删除。
5. 再 GET 一次；返回新名字，随后 TTL 又应为正数。

### 面试表达

> 骑手详情采用 Cache Aside。读请求先查 Redis，未命中时回源 MySQL 并设置十分钟 TTL；更新骑手资料时先保证 MySQL 成功，再删除缓存而不是直接改缓存，避免并发下旧数据回填问题。写请求直接读 MySQL，避免以缓存旧状态做业务决策。

---

## 3. 任务 B：Redis 挂了也不能让查询整体失败

### 为什么

缓存是“加速层”，不是唯一数据源。Redis 临时不可用时，用户仍应该能从 MySQL 查询骑手详情，只是速度变慢。

### 改哪里

仍然是 `RiderService`。把三个 Redis 动作分别保护起来：`get`、`set`、`delete`。

### 规则

- Redis `get` 抛连接异常：记录 warn 日志，继续查 MySQL。
- Redis `set` 失败：记录 warn 日志，仍然返回已查到的骑手。
- Redis `delete` 失败：记录 error/warn 日志；数据库仍已正确更新。后续可通过 TTL 或补偿任务恢复一致性。

### 提示

可以捕获 Spring Data Redis 的 `DataAccessException`。不要为了缓存失败抛 `RuntimeException`，否则一个缓存故障会把本来正常的读接口变成 500。

最小伪代码：

```text
try Redis GET
catch Redis异常 → log.warn

查 MySQL

try Redis SET
catch Redis异常 → log.warn

return 数据库结果
```

### 验证

1. 先确保缓存不存在。
2. 暂停 Redis 容器：

```bash
docker pause delivery-redis
```

3. Swagger 调用骑手详情接口；预期仍为 `200`，只是不走缓存。
4. 恢复：

```bash
docker unpause delivery-redis
```

5. 再 GET 一次，缓存应能重新生成。

### 面试表达

> Redis 被定位为缓存而不是事实来源，因此缓存访问做了降级：缓存连接失败时记录告警并回源 MySQL，保障读链路可用性。

---

## 4. 任务 C：缓存三大问题（必须会讲，先实现最有价值的一个）

### 4.1 缓存穿透：查一个永远不存在的 ID

攻击或异常客户端反复请求 `/riders/999999`：Redis 没有、MySQL 也没有，每次都压数据库。

**本项目实现：缓存空值。**

| 内容 | 设计 |
| --- | --- |
| 空值常量 | `private static final String CACHE_NULL_VALUE = "__NULL__";` |
| 不存在时 | Redis 写入该常量，TTL 60 秒 |
| 下次读取 | 读到常量直接返回“骑手不存在”，不查 MySQL |

注意：空值 TTL 必须比正常缓存短，避免新建同 ID 数据后太久不可见。

### 4.2 缓存击穿：一个非常热的 Key 刚好过期

很多人同时请求热门骑手，缓存一起未命中，全部冲到 MySQL。

**本项目先掌握方案，不把复杂锁作为必须项：**

```text
SET lock:rider:detail:1 1 NX EX 10
      ↓ 成功：查 DB、重建缓存、释放锁
      ↓ 失败：短暂退避后重查缓存
```

面试要点：分布式锁要设置过期时间，避免持锁实例崩溃后永久死锁；重建前必须二次检查缓存。

### 4.3 缓存雪崩：大量 Key 同一时间一起过期 / Redis 整体故障

应对：TTL 加随机抖动、Redis 高可用、限流与 MySQL 降级。当前项目至少做两件事：正常 TTL 加 0～60 秒随机值；Redis 故障回源 MySQL。

### 面试一句话

> 穿透用短 TTL 空值缓存，击穿用互斥重建/逻辑过期，雪崩通过随机 TTL、限流和数据库降级降低集中回源风险。

---

## 5. 任务 D：跨模块也要删骑手缓存

### 为什么

订单派单或完成时会改变骑手的 `currentOrderCount`。即使没有修改骑手资料，也必须让骑手详情缓存失效，否则详情会显示旧接单数。

### 最终目标

不要让 `DeliveryOrderService` 复制 Redis Key 拼接规则。新建一个小的缓存协作类：

```text
rider/cache/RiderCacheService
    └── evictDetail(Long riderId)
```

它只负责删除 `rider:detail:{id}` 并吞掉/记录 Redis 故障。`RiderService` 和 `DeliveryOrderService` 都注入它。

### 触发删除的场景

- 骑手资料更新。
- 骑手停用。
- 派单成功，骑手接单数 +1。
- 订单完成，骑手接单数 -1。

### 规则

所有场景都是：**对应 MySQL 更新成功后再失效缓存。**

---

# 第二阶段：实时位置与附近骑手

预计：3～4 小时。完成后，基础自动派单会从“按 ID/接单数选人”升级为“按距离找到附近候选再选择”。

## 6. 任务 E：骑手位置上报

### 真实问题

骑手每隔几十秒上报位置；派单时需要找商家附近的骑手。MySQL 可以保存最后位置，但不适合高频按距离搜索。

### 数据职责

| 数据 | 位置 |
| --- | --- |
| 骑手最后坐标（可追溯） | MySQL `rider.longitude/latitude` |
| 可用骑手的实时空间索引 | Redis GEO Key `geo:rider:available` |

### 接口设计

新建请求对象，不再拿完整 `Rider` 接收位置更新：

```text
包：rider/dto
类：RiderLocationRequest
字段：BigDecimal longitude、BigDecimal latitude
```

接口：

```text
PUT /api/v1/riders/{id}/location
Body: { "longitude": 116.397128, "latitude": 39.916527 }
```

### 业务规则（先写这些，再写代码）

1. 骑手必须存在；合法坐标会写入 MySQL。若骑手当前可接单，则同步加入 GEO；若已停用，则不会作为附近候选保留。
2. 经度必须在 `[-180, 180]`，纬度必须在 `[-90, 90]`。
3. 先更新 MySQL 最后坐标。
4. 再把骑手写入 Redis GEO。
5. 删除该骑手详情缓存，避免详情里的坐标旧。

### Redis GEO 核心

Key：`geo:rider:available`

```text
member（成员）       longitude / latitude
"rider:1"           116.397128 / 39.916527
"rider:2"           116.405000 / 39.920000
```

Java 中使用 `StringRedisTemplate.opsForGeo()`；写入本质是 Redis 的 `GEOADD`。

伪代码：

```text
检查骑手和经纬度
更新 rider 表经纬度
opsForGeo().add("geo:rider:available", Point(经度, 纬度), "rider:" + 骑手 ID)
删除 rider:detail:{id}
```

### 停用骑手时的补充

停用成功后还要从 `geo:rider:available` 移除 `rider:{id}`，否则 GEO 仍可能返回旧候选（查询最终还会回 MySQL 过滤状态）。

### 验证

创建/选择两名可用骑手，在不同坐标各上报一次位置。终端查看：

```bash
docker exec delivery-redis redis-cli GEOPOS geo:rider:available rider:1 rider:2
```

### 面试表达

> MySQL 保存骑手最后坐标作为持久化记录；Redis GEO 维护可用骑手的空间索引。位置更新后同步写两处，派单时用 GEO 在商家半径内快速过滤候选人，避免全表扫描和复杂距离计算。

---

## 7. 任务 F：查找附近可接单骑手

### 接口设计

```text
GET /api/v1/riders/nearby?longitude=116.397128&latitude=39.916527&radiusKm=3
```

新建响应对象：

```text
rider/dto/NearbyRiderResponse
字段：Long riderId、String riderNo、String name、Integer currentOrderCount、Double distanceKm
```

### 算法规则

1. 在 Redis GEO 中找半径 3 km 内、按距离升序的前 20 个 rider ID。
2. 批量/逐个查 MySQL，过滤：状态不是 AVAILABLE、已达到容量、数据库中不存在的骑手。
3. 返回距离和当前负载。

**不要直接相信 GEO 结果。** GEO 只表示“坐标上可能在附近”，可接单状态和接单数必须以 MySQL 的最新状态为准。

### Spring Data Redis 写法提示

需要的概念：`Point`、`Circle`、`Distance`、`Metrics.KILOMETERS`、`GeoRadiusCommandArgs`。IDE 里从 `opsForGeo()` 输入 `.`，让补全列出当前版本可用的 `radius` 或 `search` 方法；Spring Data Redis 版本不同，方法名可能略有差异，但语义不变：**按圆形半径查、包含距离、距离升序、限制数量。**

### 验证数据

以北京坐标为例：

```text
商家：116.397128, 39.916527
骑手 A：116.400000, 39.917000（很近）
骑手 B：116.410000, 39.930000（稍远）
骑手 C：121.473700, 31.230400（上海，应不出现）
```

### 面试表达

> GEO 返回的是空间候选集，不是最终派单结果。服务端还会根据 MySQL 中的骑手状态和接单容量做二次过滤，保证实时性与业务正确性分层处理。

---

## 8. 任务 G：让自动派单真正用“附近 + 负载”

### 当前问题

当前 `dispatch` 只选接单数最少的可用骑手，没有考虑商家距离，可能把很远的骑手派过去。

### 新的选择规则

```text
1. 用商家经纬度找 3 km 内前 20 个 GEO 候选
2. MySQL 过滤可用、未满载的骑手
3. 排序：当前接单数 ASC → 距离 ASC → ID ASC
4. 选择第一个，再进入并发安全的“实际占位”步骤
```

为什么负载优先、距离第二：同一片区里不能永远把单派给最近的人，否则会出现有人过载、有人空闲。这个规则简洁、可解释，适合面试项目。

### 注意

“选到候选骑手”不等于“已经成功派单”。多人同时派单时，候选人随时可能被别人抢满，因此下一阶段必须用原子条件更新做最终占位。

---

# 第三阶段：并发正确性与 Redis Lua

预计：4～5 小时。这是项目最重要的技术亮点之一。

## 9. 任务 H：先让 MySQL 保证不会超接单

### 真实并发问题

骑手当前接单数是 2，最大容量是 3。两个请求同时读到 2，都认为能接单，最终可能把两笔订单都分给同一骑手。

当前代码的危险写法是：

```text
先查 currentOrderCount → Java + 1 → updateById
```

这是“读—改—写”三步，在并发下不是原子的。

### 先定义容量

创建增量 SQL 文件：`src/main/resources/sql/02-concurrency.sql`。

```sql
ALTER TABLE rider
    ADD COLUMN max_order_count INT NOT NULL DEFAULT 3;
```

实体 `Rider` 加：

```java
private Integer maxOrderCount;
```

每位骑手默认最多同时配送 3 单。演示时这个数字足够直观。

### Mapper 要新增的两个“条件更新”方法

`RiderMapper`：只有仍可接单且未满载时才自增，返回受影响行数。

```sql
UPDATE rider
SET current_order_count = current_order_count + 1
WHERE id = #{riderId}
  AND status = 1
  AND current_order_count < max_order_count
```

`DeliveryOrderMapper`：只有订单仍待派单时才绑定骑手。

```sql
UPDATE delivery_order
SET rider_id = #{riderId}, status = 1
WHERE id = #{orderId}
  AND status = 0
```

### 为什么返回 `int` 很重要

```text
返回 1：本次条件成立，真正占到一个名额
返回 0：有人先改了数据；本次不能派，必须换候选人或报失败
```

### 派单事务顺序

```text
@Transactional
找到附近候选骑手
    ↓
对候选骑手执行“条件自增”
    ↓ 返回 0：换下一个候选
    ↓ 返回 1：继续
订单执行“仅待派单才绑定骑手”
    ↓ 返回 0：抛运行时异常，事务回滚，骑手刚增加的计数也回滚
成功：提交事务
```

### 你必须记住

事务解决“一组数据库操作同生共死”；条件更新解决“多个并发请求只能有一个占到同一条件”。两者一起才可靠。

### 验证

1. 将一名骑手 `max_order_count` 设为 1。
2. 连续创建两笔待派单订单。
3. 用两个 Swagger 页面或并发 curl 同时派给该骑手。
4. 预期：至多一笔订单成功，另一笔返回“暂无可用骑手”或“骑手已满载”；数据库接单数不会超过 1。

### 面试表达

> 我没有在 Java 内存里先判断再加一，而是在 MySQL 使用带容量条件的 `UPDATE` 作为最终占位。受影响行数为 1 才说明占位成功；再用事务绑定订单，任何一步失败都会回滚，因此可避免同一骑手并发超接单和同一订单重复派单。

---

## 10. 可选扩展：Redis Lua 骑手容量闸门（当前未实现，不属于验收必做）

> 当前项目的最终容量正确性由 MySQL 条件更新保障；Lua 仅用于缓存锁的安全释放。下面是可选学习实验，不要把它描述成项目已经实现的能力，也不要为了“多一个 Redis 技术点”直接接入派单主链路。

### Lua 是什么

Lua 脚本会由 Redis 单线程连续执行。脚本中的“读当前负载 → 判断上限 → 加一”不会被其他 Redis 命令插队，因此是一段原子操作。

### Key 设计

```text
rider:load:{riderId}     当前内存接单数
```

### 脚本逻辑

```lua
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local max = tonumber(ARGV[1])
if current >= max then
    return 0
end
return redis.call('INCR', KEYS[1])
```

返回值含义：`0` 是满载；大于 0 是占位后最新的接单数。

### Java 调用提示

使用 `DefaultRedisScript<Long>` + `StringRedisTemplate.execute(...)`，传入：

```text
KEYS[1] = rider:load:1
ARGV[1] = 3
```

### 非常重要：不要假装 Redis + MySQL 自动强一致

Redis Lua 和 MySQL 事务不是同一个事务。正确的渐进式做法是：

1. **本项目的最终正确性仍由上一任务的 MySQL 条件更新保证。**
2. Lua 先作为高频快速容量闸门 / 学习实验。
3. 若 Lua 占位成功但 MySQL 事务失败，必须补偿执行 `DECR rider:load:{id}`。
4. 服务重启或 Redis 丢失时，`rider:load` 要从 MySQL `current_order_count` 重建；不能盲目信任旧缓存。

### 面试表达

> Redis Lua 能把读取、判断和自增合并成原子脚本，适合高频容量闸门。但 Redis 和 MySQL 没有天然分布式事务，所以我把 MySQL 条件更新作为最终正确性保障；Lua 失败或数据库失败时有补偿与重建思路，避免夸大“绝对一致”。

---

# 第四阶段：当前消息队列方案——Redis Stream + Transactional Outbox

当前代码已经实现订单 + Outbox 同事务落库、Outbox 发布器、Stream 消费者组、ACK 和 Pending 恢复。本阶段剩余任务是按 `LEARNING_ROADMAP.md` 验收端到端行为、复核失败重试与幂等；不需要再安装 RabbitMQ。

```text
同一 MySQL 事务：订单 + Outbox(status=0)
→ Outbox 发布器：XADD Redis Stream → 更新 status=1
→ Stream 消费者组：GEO 候选 + MySQL 条件派单
→ 成功 ACK；失败留在 Pending，超时 claim 后重试
```

Outbox 的 `status=1` 仅表示事件进入 Redis Stream，不代表派单业务完成。XADD 与 Outbox 状态更新无法组成跨 MySQL/Redis 的原子事务；故障窗口可能产生重复事件，因此采用至少一次投递和消费者幂等。

<details>
<summary>历史备选方案：RabbitMQ（当前项目不采用，请勿按此安装或实现）</summary>

## 11. 任务 J：启动 RabbitMQ

首次拉镜像需要网络；拉完后本地就能离线使用。

```bash
docker run -d --name delivery-rabbitmq \
  --restart unless-stopped \
  -p 5672:5672 -p 15672:15672 \
  rabbitmq:3.13-management
```

管理台：`http://localhost:15672`，本地默认账号密码均为 `guest`。

在 `pom.xml` 加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

在 `application.yml` 加本地连接：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    publisher-confirm-type: correlated
    publisher-returns: true
```

### 需要理解的 4 个角色

```text
生产者 → Exchange（交换机）→ Queue（队列）→ 消费者
```

- 生产者：订单服务。
- Exchange：根据 routing key 决定去哪里。
- Queue：临时保存消息、实现削峰。
- 消费者：异步执行通知、日志、重试派单等动作。

---

## 12. 任务 K：订单派单成功后发布事件

### 为什么不能在事务中直接发 MQ

如果先发消息、随后数据库事务回滚，消费者会收到一条“实际上并未派单成功”的幽灵消息。

### 正确顺序

```text
事务内：更新订单 + 更新骑手接单数
        ↓
事务提交成功（AFTER_COMMIT）
        ↓
发送 OrderAssignedEvent 到 RabbitMQ
```

### 建议目录

```text
order/event/OrderAssignedEvent.java
order/event/OrderAssignedDomainEvent.java
order/mq/OrderEventPublisher.java
order/mq/OrderAssignedConsumer.java
config/RabbitMqConfig.java
```

### 事件字段

```text
eventId：UUID，用来幂等
orderId、orderNo、riderId、occurredAt
```

### 事件发布方式

事务方法内不直接 `rabbitTemplate.convertAndSend`。先发布 Spring 本地事件；另一个监听器使用：

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
```

在该监听器内才调用 RabbitTemplate。

### 消费者第一版做什么

先做“模拟通知骑手”：记录 `orderNo`、`riderId`、事件 ID 的日志。它不影响派单主链路，因此消费者挂了也不会导致订单回滚。

### 面试表达

> 派单成功后将通知类操作异步化。事件只在数据库事务提交后投递，避免回滚后仍产生消息；生产者与通知消费者通过 RabbitMQ 解耦，主请求无需等待通知完成。

---

## 13. 任务 L：失败重试、死信队列与幂等

### 为什么需要

MQ 通常至少一次投递：同一消息可能重复到达。消费者调用外部服务时也可能失败。

### 幂等设计

新增表 `delivery_event_log`，用 `event_id` 做唯一索引：

```sql
CREATE TABLE delivery_event_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status TINYINT NOT NULL,
    error_message VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id)
);
```

消费逻辑：

```text
收到 eventId
→ event_id 已处理：直接确认消息，不重复通知
→ 未处理：执行业务、写处理记录、确认消息
→ 连续失败：按重试策略进入死信队列，人工/定时任务排查
```

### 重试策略

第一版可用 Spring AMQP 的消费者重试：最大 3 次；失败后进入 DLQ（Dead Letter Queue）。DLQ 是“失败邮件收件箱”，不是直接丢弃。

### 延迟重新派单（加分项）

当附近没有可用骑手时：订单保持 `PENDING_DISPATCH`，发送到一个 TTL 为 30 秒的延迟队列；消息超时后经死信交换机路由到“重新派单队列”；消费者再次尝试 `dispatch(orderId)`。

再次派单前必须检查：订单仍是待派单。若用户已取消或已派单，直接确认消息，不做任何变更。

---

</details>

# 第五阶段：可观测性、JVM 与测试

预计：2.5～3.5 小时。

## 14. 任务 M：Actuator 健康与指标

当前 `application.yml` 已暴露 `health,info,metrics`。修改配置后要重启应用，再访问：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

重点查看：

```text
/actuator/health
/actuator/metrics/jvm.memory.used
/actuator/metrics/jvm.gc.pause
/actuator/metrics/http.server.requests
```

面试表达：

> 我用 Actuator 暴露健康检查和 JVM/HTTP 指标，至少能快速区分服务是否存活、内存是否异常、接口延迟是否上升。

## 15. 任务 N：JVM 最小实战

### JVM 内存先记这四块

| 区域 | 白话 |
| --- | --- |
| 堆（Heap） | Java 对象主要存放处，最常发生 GC |
| 栈（Stack） | 每个线程的方法调用、局部变量 |
| 元空间（Metaspace） | 类元数据 |
| 直接内存 | NIO、网络缓冲区等可能使用 |

### 在 IDEA Run Configuration 的 VM options 加

```text
-Xms256m -Xmx256m -Xlog:gc*:file=logs/gc.log:time,level,tags
```

启动前创建目录：

```bash
mkdir -p logs
```

### 常用排查命令

先找到 Java PID：

```bash
jps -l
```

再执行：

```bash
jcmd <PID> GC.heap_info
jcmd <PID> Thread.print
jcmd <PID> VM.flags
```

### 遇到接口变慢的排查顺序

```text
1. Actuator 看 HTTP 延迟和错误率
2. 看 CPU、线程数、GC pause
3. Thread.print 看是否大量线程阻塞
4. GC 日志看是否频繁 Full GC
5. 再回到慢 SQL、Redis、MQ 连接等依赖
```

不要在面试里说“JVM 调优就是调大堆内存”。先定位，再调整。

## 16. 任务 O：测试清单

### 单元测试

保留并增加以下测试：

- 已派单订单才能开始配送。
- 配送中订单完成后骑手接单数减一。
- 已派单订单不能取消。
- 超过骑手容量时条件更新失败。
- 同一订单重复派单时，只有一次成功。

### 手工 Swagger 验收流程

```text
1. 创建可用骑手 A、B
2. 上报 A、B 坐标
3. 查询商家附近骑手，确认远处骑手不出现
4. 创建订单
5. 自动派单，确认选的是附近且低负载骑手
6. 开始配送、完成订单
7. 查看骑手 currentOrderCount 已减少
8. 查看 Redis 详情缓存被正确失效与回填
9. 查看 `dispatch_outbox` 状态、Redis Stream 事件、消费者日志及订单最终状态
```

### 并发演示

不要虚构 QPS。可以用两个终端同时发请求，或者安装好 ApacheBench / JMeter 后压同一个派单接口。记录：总请求数、成功数、失败数、最终数据库订单数与骑手接单数；核心验收是“没有超容量、没有重复派单”。

---

# 第六阶段：收尾、简历与面试

预计：2～3 小时。

## 17. README 最终必须有的内容

- 项目背景：为什么是同城配送调度。
- 技术栈和每个技术解决的问题。
- 架构图 / 业务流图。
- Docker 启动方式（不含真实密码）。
- Swagger 测试顺序。
- Redis、GEO、并发、MQ 的关键设计。
- 已知取舍：本项目不包含真实地图、支付和微服务。

## 18. 简历项目描述（如实填写）

> **同城配送智能调度与履约平台｜Java 后端**
>
> - 基于 Spring Boot、MyBatis-Plus、MySQL 构建骑手、订单及配送状态机，使用事务保证派单、骑手接单数变更的一致性。
> - 使用 Redis String + Cache Aside 缓存热点骑手详情，设置 TTL、缓存失效及 Redis 故障回源 MySQL 策略；使用 Redis GEO 按商家坐标检索附近可用骑手。
> - 通过 MySQL 条件更新保证骑手容量占位和订单状态更新的并发正确性；Redis Lua 当前用于缓存重建锁的安全释放，不宣称实现了容量预占。
> - 基于 Transactional Outbox 将订单派单事件可靠投递到 Redis Stream，由消费者组异步处理；通过 Pending 恢复、ACK 和订单状态校验处理失败与重复消息。
> - 接入 Spring Boot Actuator 观察健康、HTTP 与 JVM 指标，结合 GC 日志和 `jcmd` 完成基础排障。

只有在你真的做过压测并记录结果后，才增加“在 X 并发下……”这类量化描述。

## 19. 高频面试题自测

1. 为什么订单仍放 MySQL，不完全放 Redis？
2. Cache Aside 的读、写顺序是什么？为什么？
3. Redis 挂了怎么办？
4. 缓存穿透、击穿、雪崩分别是什么？
5. 为什么 GEO 结果还要回 MySQL 过滤？
6. 为什么“先查接单数再 +1”会超接单？
7. `UPDATE ... WHERE current_order_count < max_order_count` 如何保证并发安全？
8. `@Transactional` 能解决 Redis 和 MySQL 的一致性吗？为什么不能？
9. Lua 为什么原子？Lua 失败补偿怎么做？
10. 为什么 Outbox 要和订单在同一个 MySQL 事务里？
11. Outbox 已投递为什么不等于派单成功？
12. Redis Stream 的 Pending、claim、ACK 分别解决什么问题？
13. XADD 成功但 Outbox 更新失败为什么可能重复，消费者怎么幂等？
14. Actuator、GC 日志、线程栈各看什么？

### 标准回答骨架

```text
先说业务风险
→ 再说采用的技术机制
→ 再说边界/失败时怎么办
→ 最后说如何验证
```

例如回答缓存：

> 热点骑手详情若每次查询都落 MySQL，会造成重复读压力。因此我采用 Cache Aside，读时 Redis 未命中才回源数据库并设置 TTL；写时先更新数据库再删除缓存。Redis 出现连接故障时降级读 MySQL。对于不存在数据会用短 TTL 空值缓存防穿透，热点 Key 则可用互斥重建防击穿。

---

# 附录 A：推荐的离线执行顺序与时间盒

| 顺序 | 任务 | 最多投入 |
| --- | --- | --- |
| 1 | 缓存正确闭环：任务 A、B | 2 小时 |
| 2 | 穿透知识 + 空值缓存，缓存失效统一 | 1.5 小时 |
| 3 | 位置上报、GEO 附近查询 | 3 小时 |
| 4 | 附近 + 负载自动派单 | 1 小时 |
| 5 | MySQL 条件更新防超接单 | 2.5 小时 |
| 6 | Redis Stream + Outbox 联调、幂等复核 | 2～3 小时 |
| 7 | 并发测试、Actuator/JVM/README/简历 | 3～4 小时 |

此前按 RabbitMQ 和骑手容量 Lua 预占安排的时间估算已过时。当前实际剩余进度以 [`LEARNING_ROADMAP.md`](LEARNING_ROADMAP.md) 的六个阶段为准；剩余时间主要花在真实联调、并发/消息边界验证、JVM 和面试表达。

# 附录 B：离线资料入口

- `REDIS_STUDY_NOTES.md`：Redis 所有知识的总笔记。
- `lessons/0001-order-state-machine.html`：订单状态机。
- `lessons/0002-why-redis-before-geo.html`：为什么配送项目需要 Redis。
- `lessons/0003-redis-key-string-ttl.html`：Key、String、TTL。
- `lessons/0004-cache-aside-rider-detail.html`：缓存旁路模式。
- `RESOURCES.md`：联网后可看的官方资料和课程。

> 离线时做完一个任务，先写一句自己的复盘：**“我解决了什么问题，为什么不用更简单但错误的方案？”** 这句话就是面试表达的起点。
