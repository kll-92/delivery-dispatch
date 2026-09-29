# 技术设计

## 前端技术

- React + Vite
- TypeScript
- React Router
- Axios 或原生 `fetch` 统一调用后端
- CSS Modules 或普通 CSS，先保持依赖简单

## 建议目录

```text
frontend/
├── src/
│   ├── api/           # 后端接口封装
│   ├── components/    # 表格、状态标签、操作按钮
│   ├── pages/         # 总览、骑手、订单页面
│   ├── types/         # Rider、DeliveryOrder 类型
│   ├── App.tsx
│   └── main.tsx
└── package.json
```

## 调用原则

- 页面组件不直接拼接请求 URL，统一通过 `src/api` 调用。
- 操作按钮必须有 loading 状态，防止重复提交。
- 后端返回错误时统一转换为用户能看懂的提示。
- 前端只负责展示和发起操作，订单状态规则仍由后端负责。

## 开发顺序

先做静态布局，再接骑手列表，再接订单列表，最后接派单和状态操作。
