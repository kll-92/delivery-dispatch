# ========== 阶段 1：构建前端静态文件 ==========
FROM node:22-alpine AS build

WORKDIR /app

# 先拷依赖清单，命中缓存
COPY package*.json ./
RUN npm ci || npm install

COPY . .
RUN npm run build

# ========== 阶段 2：用 nginx 托管 ==========
FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
