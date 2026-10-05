# 来现场 · 活动票务与候补平台

一个可在自己电脑上运行的 Java 全栈学习项目：手机号模拟登录、活动管理、购票、候补、模拟付款与退款。网页随 Spring Boot 应用一起提供，不需要另装前端构建工具。

交易使用 MySQL 事务、条件更新与唯一约束；Redis 用于登录会话、限流、目录缓存和 Stream 消息。重点可以学习“先持久受理、再异步处理”、幂等、库存与资格约束、订单到期释放、失败重试及候补补位。

短信、付款与退款都是模拟，不接真实短信服务、资金或固定选座。这个入口只允许本机访问，用于学习与演示。

## 快速启动

准备 **JDK 17、Maven 3.9+、Docker Compose、PowerShell 7.2+**。确认 `java -version`、`mvn -version`、`docker compose version` 和 `pwsh --version` 可运行，Docker 已启动。首次构建和容器拉取需要联网。

```powershell
git clone https://github.com/666yg1433223-sys/laixianchang-ticketing.git
cd laixianchang-ticketing

# 为自己的学习环境生成四个随机密码，保存在被 Git 忽略的 .env 中。
# 重复执行不会覆盖已有文件，不读取原开发机器的 .local。
pwsh -File scripts/run-learning.ps1 -Action Init

# 仅启动这个项目独立的 MySQL / Redis，等待健康检查通过。
docker compose up -d --wait

# 从源码构建，再以前台方式启动；保持此窗口打开。
mvn -DskipTests package
pwsh -File scripts/run-learning.ps1
```

打开 **<http://127.0.0.1:18082>**。首次启动由 Flyway 创建业务表；这是空白学习库，不包含原作者的用户、订单或历史活动。此后改代码可以执行 `pwsh -File scripts/run-learning.ps1 -Build` 重新打包启动。

按 `Ctrl+C` 停止网站，再用 `docker compose stop` 停止依赖。重新体验时运行 `docker compose up -d --wait` 和启动脚本即可，数据保存在 Docker 卷中；不要随意删除数据卷。

| 内容 | 本机地址 / 名称 |
| --- | --- |
| 网页与 API | `127.0.0.1:18082` |
| MySQL | `127.0.0.1:13316`，库 `ticketing_learning` |
| Redis | `127.0.0.1:16389` |
| MySQL 业务账号 | `ticketing_learning_app`，仅业务表读写 |
| MySQL 迁移账号 | `ticketing_learning_migrator`，仅学习库建表迁移 |

学习启动入口与历史本机环境使用不同数据库、端口和密码。MySQL 的初始化脚本仅在空数据卷上运行；数据卷已存在后修改 `.env` 不会自动修改 MySQL 账号密码，应恢复匹配的原 `.env` 或自行迁移凭据。

## 登录并创建第一场活动

1. 网页输入手机号，点击“获取验证码”。
2. 在运行 Java 的终端找到最新的 `LOCAL_SIM_SMS` 日志，把其中 `code=` 后的六位数字填回网页。日志会显示脱敏手机号，HTTP 响应不会返回验证码。
3. 用本地管理员手机号 **`13900000000`** 登录，在“活动管理”中点击“创建活动”，再添加场次和票档，最后“发布场次”。开售时间设为当前时间之前，停售时间设为活动开始之前。
4. 退出后换一个合法手机号登录，即可作为普通观众体验购票。首次登录自动注册，每人每场次最多保留一个有效订单或候补资格，每次一张票。

验证码有效期为两分钟，同一手机号 60 秒内不能重复发送。未收到日志时，先检查是否触发页面限流提示；不要尝试用任意固定验证码登录。

可以给票档设置少量库存，用不同账号体验“售罄 → 加入候补 → 原订单取消/到期/退款 → 候补得到待付款订单”。候补不会自动扣款。“已受理”只是请求已保存，待订单显示“已付款”才表示模拟付款结算成功。

待付款订单限时十分钟（不晚于停售时间），可以主动取消。已付款订单在活动开始前可申请全额模拟退款；退款成功后才释放名额，原付款记录保留。

## 不使用 PowerShell / Docker

PowerShell 脚本只是辅助入口。macOS / Linux 可使用以下方式生成自己的 `.env`，然后沿用上面的 Compose 和 Maven 命令：

```bash
# 只在首次没有 .env 时执行，防止覆盖已有数据库密码。
if [ ! -e .env ]; then
  (umask 077
   for key in TICKETING_MYSQL_ROOT_PASSWORD TICKETING_DB_PASSWORD TICKETING_MIGRATOR_PASSWORD TICKETING_REDIS_PASSWORD; do
     printf '%s=%s\n' "$key" "$(openssl rand -hex 32)"
   done > .env)
fi

docker compose up -d --wait
mvn -DskipTests package
set -a
. ./.env
set +a
java -Dfile.encoding=UTF-8 -jar target/ticketing-booking-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local-sim --biz.learning.enabled=true \
  --biz.db.name=ticketing_learning --biz.db.user=ticketing_learning_app \
  --biz.db.migrator=ticketing_learning_migrator --biz.db.port=13316 --biz.redis.port=16389
```

也可以自行提供 **MySQL 8.4 与 Redis 7.4**，按上表监听本机端口，建立 `ticketing_learning` 和两类账号。账号授权见 [MySQL 初始化脚本](infra/learning/mysql-init.sh)，将里面两处密码变量替换为自己的 `.env` 值后通过 MySQL 客户端执行相同 SQL；Redis 开启对应密码认证。然后跳过 Compose，直接构建启动。MySQL 的本机学习连接不启用 TLS，启动守卫会拒绝更换成外网地址或旧业务库。

## 源码学习路线

| 顺序 | 入口 | 可以跟着追的问题 |
| --- | --- | --- |
| 1 | [网页](src/main/resources/static)、[AuthService](src/main/java/com/ticketing/booking/auth/AuthService.java) | 手机号如何发码、校验、创建用户和保存会话？ |
| 2 | [CatalogueService](src/main/java/com/ticketing/booking/catalogue/CatalogueService.java)、[CatalogueCache](src/main/java/com/ticketing/booking/catalogue/CatalogueCache.java) | 草稿如何发布？缓存失效和数据库事实如何区分？ |
| 3 | [BookingService](src/main/java/com/ticketing/booking/service/BookingService.java)、[BookingStream](src/main/java/com/ticketing/booking/product/BookingStream.java) | 为什么受理请求先写 MySQL？Outbox 如何投递和认领？ |
| 4 | [AllocationService](src/main/java/com/ticketing/booking/service/AllocationService.java)、[建表迁移](src/main/resources/db/migration) | 重复消费为什么不会重复出票？库存与用户资格靠什么约束？ |
| 5 | [PaymentService](src/main/java/com/ticketing/booking/service/PaymentService.java)、[OrderCloseService](src/main/java/com/ticketing/booking/service/OrderCloseService.java) | 付款与超时关闭如何竞争？迟到结果如何处理？ |
| 6 | [WaitlistService](src/main/java/com/ticketing/booking/product/WaitlistService.java)、[RefundService](src/main/java/com/ticketing/booking/service/RefundService.java) | 候补如何补位？退款成功后何时释放库存？ |
| 7 | [测试目录](src/test/java/com/ticketing/booking)、[接口示例](docs/product-api.http) | 如何验证幂等、事务回滚、失败重试与恢复？ |

数据库迁移按 V1～V7 逐步演进，已应用的 SQL 不应直接改写；新增结构用新的迁移版本。网页接口返回的 ID 使用字符串，金额使用整数分；Redis 展示余量可能短暂滞后，交易是否成功最终以 MySQL 中的订单状态为准。

## 构建与测试

```powershell
# 可在任意克隆目录运行的离线测试（排除两个绑定旧本机路径的历史测试）
mvn "-Dtest=*OfflineTest,!DemoClasspathOfflineTest,!OrderContractOneOfOfflineTest" test

# 打包；只跳过测试执行，不跳过源码编译
mvn -DskipTests package
```

`IntegrationTest`、旧演示脚本与部分真实环境回归依赖原开发机器的隔离测试库和私有夹具，不能直接对新学习库运行。`DemoClasspathOfflineTest` 与 `OrderContractOneOfOfflineTest` 还绑定了旧开发目录或本机 Maven 缓存，因此上面的可移植命令显式排除这两项历史测试，没有把完整测试默认连接到朋友的数据库。

2026-10-05 在原开发目录与缓存下执行全部 `*OfflineTest`：179 项通过，失败、错误、跳过均为 0；JAR 打包通过。学习启动脚本的语法、随机配置生成、已有配置保留、非法配置拒绝及 Compose 配置解析也已检查。由于当时 Docker 引擎未连接，本次未验证新学习容器的完整启动与网页操作；旧开发环境的历史测试次数不能算作新环境实测结果。

技术版本以 [pom.xml](pom.xml) 为准。主要代码使用 Spring Boot、Spring JDBC / MySQL、Flyway、Lettuce、Redis Stream 与 Redisson；仓库中保留的依赖并不表示每种框架都用于核心交易链。

`.env`、`.local`、`.m2`、日志、构建产物和原开发证据均不提交。请保管自己的 `.env`，不要连同数据库备份公开上传。

原开发机器的运行方式、阶段记录及历史验证说明保存在 [历史开发记录](docs/history/local-development.md)，不作为新同学的启动步骤。项目由 AI 辅助开发，欢迎从调用链和测试开始理解业务取舍。
