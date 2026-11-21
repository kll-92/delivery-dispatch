# ============================================================
# 阶段 1：构建 —— 用 Maven 镜像把源码打成 jar
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 配置阿里云 Maven 镜像：直连 Maven Central 在国内经常被截断（Premature end of Content-Length）
COPY docker/maven/settings.xml /root/.m2/settings.xml

# 先只拷 pom：依赖没变时这一层命中缓存，不用重新下载。
# 加 `|| true`：预下载只是加速手段，网络抖动不该让整个构建失败；
# 真正需要的依赖会在下一步 package 时补齐。
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline || true

# 再拷源码并打包（跳过测试：测试在 CI/本地跑，镜像只负责产物）
COPY src ./src
RUN mvn -B -ntp -DskipTests package

# ============================================================
# 阶段 2：运行 —— 只带 JRE 和 jar，镜像从 ~800MB 降到 ~200MB
# ============================================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 用非 root 用户运行：容器被攻破时影响面更小
RUN addgroup -S app && adduser -S app -G app

# --chown 直接带权限拷贝，避免多一个 chown 层把 jar 又复制一遍（白占 90MB）
COPY --chown=app:app --from=build /build/target/*.jar app.jar
USER app

EXPOSE 8080

# 容器时区与 JVM 参数
ENV TZ=Asia/Shanghai
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
