# DispatchOS 前端

基于 React、Vite 与 TypeScript 的配送调度运营界面，保留三种布局原型。

## 启动

```bash
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 会将 `/api` 请求代理到 `http://localhost:8080`。

## 登录与访客演示

未登录时只显示内置演示数据。访客创建、修改、派单、履约和删除等操作仅在当前页面生效，不会请求真实业务写接口。点击“运营人员登录”后，前端先检查 Session；登录成功后才读取业务分页数据。所有写请求先获取 CSRF 令牌，并携带 Session Cookie。登录后可使用“退出”清除当前会话。

项目不提供默认账号或密码。首次管理员账号应由后端通过 `BOOTSTRAP_ADMIN_PASSWORD` 配置初始化；请勿将凭据写入前端代码或文档。管理员可管理骑手、费用、订单记录、运营账号和操作记录；调度员拥有订单履约相关权限。页面会按角色收敛操作入口，后端仍负责最终权限校验。

## 布局原型

开发模式下可以使用页面底部的切换器，或直接访问：

- `http://localhost:5173/?variant=A`：Command Center
- `http://localhost:5173/?variant=B`：Operations Board
- `http://localhost:5173/?variant=C`：Dispatch Desk
