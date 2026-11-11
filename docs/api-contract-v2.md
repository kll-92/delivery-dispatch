# 三端改造 · 接口契约 v2

> 目的：在写代码之前把「谁能调什么、数据归谁」定死。前后端都照这份写，接口不变就不用返工。
> 状态：**草案，待确认**。

---

## 1. 角色模型

现状：`app_account.role` 只有 `ADMIN`、`DISPATCHER`，订单没有归属，骑手没有登录身份。

改造后：

| 角色 | 说明 | 典型账号 |
|---|---|---|
| `ADMIN` | 平台管理员，全部权限 | admin |
| `DISPATCHER` | 调度员，管订单和骑手，不管账号 | dispatcher01 |
| `MERCHANT` | 商家，只能看和操作**自己的**订单 | m_blacktea |
| `RIDER` | 骑手，只能看**派给自己**的订单 | r_1001 |

### 权限矩阵

| 动作 | ADMIN | DISPATCHER | MERCHANT | RIDER |
|---|:---:|:---:|:---:|:---:|
| 创建订单 | ✓ | ✓ | ✓（归属自己） | ✗ |
| 查看订单列表 | 全部 | 全部 | 仅自己 | 仅自己 |
| 查看订单详情 | 全部 | 全部 | 仅自己 | 仅自己 |
| 修改配送费 | ✓ | ✗→✓ | 仅自己且未派单 | ✗ |
| 派单 / 重新派单 | ✓ | ✓ | ✗ | ✗ |
| 开始配送 / 送达 | ✓ | ✓ | ✗ | ✓（仅自己接的单） |
| 取消订单 | ✓ | ✓ | 仅自己且未派单 | ✗ |
| 删除订单 | ✓ | ✗ | ✗ | ✗ |
| 骑手管理（增删改查） | ✓ | ✓ | ✗ | ✗ |
| 附近骑手查询 | ✓ | ✓ | ✗ | ✗ |
| 上报自己的位置 | ✗ | ✗ | ✗ | ✓ |
| 账号管理 / 操作日志 | ✓ | ✗ | ✗ | ✗ |
| 数据看板 | ✓ | ✓ | 仅自己 | 仅自己 |

> `DISPATCHER` 能否改配送费：现状是仅 ADMIN。建议放开给 DISPATCHER，因为调度员实际会调价。**这条待你确认。**

---

## 2. 数据模型变更

```sql
-- 06-multi-tenant.sql

-- ① 账号增加显示名（商家/骑手在界面上显示的名字）
ALTER TABLE app_account ADD COLUMN display_name VARCHAR(64) NULL AFTER username;

-- ② 商家实体表（登录凭证与业务实体解耦）
CREATE TABLE merchant (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,              -- 关联 app_account.id
  name      VARCHAR(64)  NOT NULL,         -- 店铺名
  phone     VARCHAR(20)  NOT NULL,
  address   VARCHAR(255) NOT NULL,         -- 取货地址
  longitude DECIMAL(10,7) NOT NULL,
  latitude  DECIMAL(10,7) NOT NULL,
  enabled   TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_merchant_account (account_id)
);

-- ③ 订单归属：指向 merchant.id
ALTER TABLE delivery_order ADD COLUMN merchant_id BIGINT NULL AFTER id;
CREATE INDEX idx_order_merchant ON delivery_order (merchant_id);

-- ④ 骑手绑定登录账号（一个账号最多绑定一个骑手）
ALTER TABLE rider ADD COLUMN account_id BIGINT NULL AFTER id;
CREATE UNIQUE INDEX uk_rider_account ON rider (account_id);
```

**设计取舍**（面试会被问）：

- 订单**同时保留** `merchant_name/phone/address` 快照和 `merchant_id` 引用。这是有意的：订单是历史凭证，商家改名换址后，历史订单必须保留下单时的信息。**快照 + 引用**是订单类系统的标准做法。
- **新建 `merchant` 表，不复用 `app_account`**。理由：账号是认证实体（用户名、密码、角色、启用状态），商家是业务实体（店名、地址、坐标、营业状态）。两者属性不同、生命周期也不同——禁用账号不等于关店。`merchant.account_id` 唯一索引保证一个账号只对应一个店铺。
- `rider.account_id` 唯一索引：一个骑手只能有一个登录账号，反过来一个账号也只能绑一个骑手。

---

## 3. 接口清单

### 3.1 保持不变的（只加鉴权）

| 方法 | 路径 | 变化 |
|---|---|---|
| POST | `/api/v1/auth/login` | 无 |
| GET | `/api/v1/riders` `/page` `/{id}` | 加 `MERCHANT`/`RIDER` 拒绝 |
| PATCH | `/api/v1/orders/{id}/assign/{riderId}` | 仅 ADMIN/DISPATCHER |
| GET | `/api/v1/dashboard/summary` | 按角色过滤数据 |

### 3.2 修改的

| 方法 | 路径 | 变化 |
|---|---|---|
| POST | `/api/v1/orders` | `MERCHANT` 调用时，`merchant_id` 强制取当前登录账号，**忽略请求体里的值** |
| GET | `/api/v1/orders` `/page` | `MERCHANT` 只返回 `merchant_id = 自己`；`RIDER` 只返回 `rider_id = 自己` |
| PATCH | `/api/v1/orders/{id}/fee` | 放开给 `DISPATCHER`；`MERCHANT` 只能改自己且状态为待派单 |

### 3.3 新增（骑手端）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/rider/me/orders` | 我的订单（已派给我且未完成） |
| POST | `/api/v1/rider/me/location` | 上报自己的位置，**不接受 riderId 参数**，从登录账号解析 |
| PATCH | `/api/v1/rider/me/orders/{orderId}/accept` | 接单（幂等：重复接单返回 200） |
| PATCH | `/api/v1/rider/me/orders/{orderId}/complete` | 送达 |
| GET | `/api/v1/rider/me/profile` | 我的信息 + 当前接单数 + 位置是否新鲜 |

> 骑手端接口**一律不带 riderId**，从 `SecurityContext` 里解析当前账号绑定的骑手。这是防越权的核心——客户端传什么 id 都不能信。

### 3.4 新增（商家端）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/merchant/me/orders` | 我的订单（分页） |
| GET | `/api/v1/merchant/me/summary` | 我的今日单量 / 完成率 / 平均时长 |

---

### 3.5 注册接口（商家自助注册）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register/merchant` | 公开接口。`username` + `password` + `displayName`（店铺名）→ 创建 `MERCHANT` 账号 |

约束（每条都是防事故的）：

- **请求体不接受 `role` 字段**，服务端硬编码为 `MERCHANT`。否则任何人都能注册管理员
- 密码 ≥ 8 位且 UTF-8 不超过 72 字节（沿用 BCrypt 限制）
- 用户名唯一，冲突返回 `409`
- 注册后 `enabled = true` 直接可用。将来要「注册 + 审核」，只需把默认值改为 `false`，管理员启用即可——**复用现有 `enabled` 字段，不新增状态列**
- 待办：同 IP 限流（第一版不做，但要在 README 里写明）

**骑手和平台账号不走公开注册**：

- 骑手：管理员创建骑手时自动生成账号（用户名 = `rider_no`，随机初始密码，只展示一次）
- `ADMIN` / `DISPATCHER`：只能由管理员创建

## 4. 错误码约定

沿用现有 `ApiError` 结构 `{code, message, path, timestamp}`。

| HTTP | 场景 |
|---|---|
| 400 | 参数错误 / 业务规则不满足（如位置过期、容量已满） |
| 401 | 未登录 |
| 403 | 角色无权限，或访问了不属于自己的资源 |
| 404 | 资源不存在 |
| 409 | 状态冲突（如订单已完成不能再派单） |

> 越权访问**统一返回 403 而不是 404**，理由：对内部系统，明确告知无权限比伪装成不存在更利于排查。如果将来对外，再改成 404 防探测。

---

## 5. 前端页面 ↔ 接口映射

| 页面 | 路由 | 主要接口 |
|---|---|---|
| 登录 | `/login` | POST /auth/login |
| 平台·订单 | `/admin/orders` | GET /orders/page、PATCH assign、PATCH dispatch |
| 平台·骑手 | `/admin/riders` | GET /riders/page、POST /riders、GET /riders/nearby |
| 平台·看板 | `/admin/dashboard` | GET /dashboard/summary |
| 商家·我的订单 | `/merchant/orders` | GET /merchant/me/orders、POST /orders |
| 骑手·工作台 | `/rider` | GET /rider/me/orders、PATCH accept、POST location、PATCH complete |

---

## 6. 实现顺序

1. `06-multi-tenant.sql` 迁移脚本
2. `AccountRole` 加两个枚举 + `AccountService` 的角色校验放开
3. `DeliveryOrder` / `Rider` 实体加字段
4. `SecurityConfig` 新增路由的鉴权规则
5. 商家自助注册接口（`POST /api/v1/auth/register/merchant`）
6. 仓储层按 `merchant_id` / `account_id` 过滤
7. 骑手端 + 商家端接口
8. 测试：越权用例（A 商家查 B 商家订单必须 403）
9. 前端 Vue 三端

> 第 8 步是重点。**权限系统最容易被问的就是"你怎么防止越权"**，测试里必须有跨租户访问的用例。
