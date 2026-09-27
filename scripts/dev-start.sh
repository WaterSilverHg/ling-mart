#!/bin/bash
# 开发辅助：一键启动 7 个业务服务（auth/shop/product/ware/order/search/gateway；JDK21，
# nohup 脱离会话，日志在 /tmp/lingmart-logs/）。不含 chat 与可选的 agent。
set -u
export JAVA_HOME="C:\Users\p6LHG\Desktop\java\Java\jdk-21.0.12"
cd "c:\Users\p6LHG\Desktop\java\ling-mart" || exit 1
mkdir -p /tmp/lingmart-logs

nohup "$JAVA_HOME/bin/java" -jar "lingmart-auth/target/lingmart-auth-0.0.1-SNAPSHOT.jar"     > /tmp/lingmart-logs/auth.log    2>&1 &
echo "auth pid=$!"

nohup "$JAVA_HOME/bin/java" -jar "lingmart-shop/target/lingmart-shop-0.0.1-SNAPSHOT.jar"     > /tmp/lingmart-logs/shop.log    2>&1 &
echo "shop pid=$!"

nohup "$JAVA_HOME/bin/java" -jar "lingmart-product/target/lingmart-product-0.0.1-SNAPSHOT.jar" > /tmp/lingmart-logs/product.log 2>&1 &
echo "product pid=$!"

nohup "$JAVA_HOME/bin/java" -jar "lingmart-ware/target/lingmart-ware-0.0.1-SNAPSHOT.jar" > /tmp/lingmart-logs/ware.log 2>&1 &
echo "ware pid=$!"

nohup "$JAVA_HOME/bin/java" -jar "lingmart-order/target/lingmart-order-0.0.1-SNAPSHOT.jar" > /tmp/lingmart-logs/order.log 2>&1 &
echo "order pid=$!"

nohup "$JAVA_HOME/bin/java" -jar "lingmart-search/target/lingmart-search-0.0.1-SNAPSHOT.jar" > /tmp/lingmart-logs/search.log 2>&1 &
echo "search pid=$!"

nohup "$JAVA_HOME/bin/java" -jar "lingmart-gateway/target/lingmart-gateway-0.0.1-SNAPSHOT.jar" > /tmp/lingmart-logs/gateway.log 2>&1 &
echo "gateway pid=$!"
