# ============================================================
# 轻营 · 业务服务通用镜像
# 用法：构建上下文 = 各服务模块目录（目录内需有 target/*.jar，先 mvn package），
#       dockerfile 指向本文件（见 docker-compose.business.yml）。
# 例：docker compose -f docker-compose.yml -f docker-compose.business.yml build
# ============================================================
FROM eclipse-temurin:21-jre

WORKDIR /app

# 复制 Spring Boot fat jar（*.jar.original 不匹配 *.jar，仅打包产物被复制）
COPY target/*.jar app.jar

# 时区（与宿主机一致，便于日志排查）
ENV TZ=Asia/Shanghai

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
