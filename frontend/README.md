# DispatchOS 前端原型

这是配送调度项目的第一版前端原型，使用 React + Vite + TypeScript。

## 启动

```bash
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 会把 `/api` 请求代理到后端 `http://localhost:8080`。

后端未启动时，页面会自动使用演示数据，页面右上角会显示“演示数据”。后端启动后点击刷新即可重新请求真实接口。

## 布局原型

开发模式下可以使用页面底部的切换器，或直接访问：

- `http://localhost:5173/?variant=A`：Command Center
- `http://localhost:5173/?variant=B`：Operations Board
- `http://localhost:5173/?variant=C`：Dispatch Desk

这是第一版原型，后续会根据选中的布局继续重构和美化。
