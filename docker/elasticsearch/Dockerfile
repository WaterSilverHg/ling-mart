# 轻营 · Elasticsearch 镜像（内置 IK 中文分词插件）
# 构建：docker compose build elasticsearch
# 注意：IK 插件版本必须与 ES 版本严格一致（8.15.5 → v8.15.5）
FROM elasticsearch:8.15.5

# 在线安装 IK 分词插件（使用官方 CDN 地址，下载更稳定）
RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch \
    https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.15.5.zip

# 若 GitHub 下载受限，可改为手动方案：
#   1. 下载 elasticsearch-analysis-ik-8.15.5.zip 放到本目录
#   2. 注释上面的 RUN，改用下面两行：
# COPY elasticsearch-analysis-ik-8.15.5.zip /tmp/ik.zip
# RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/ik.zip