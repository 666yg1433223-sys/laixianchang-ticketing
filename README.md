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

## 代码怎么找

Java 源码根目录是 [`src/main/java/com/ticketing/booking`](src/main/java/com/ticketing/booking)。接口统一放在 `controller`，核心业务按照接口、实现、数据访问分开：

```text
com.ticketing.booking
├── controller          HTTP 请求解析、调用业务、返回结果
├── service             业务接口
│   └── impl            业务规则、事务、加锁与状态流转
├── repository          Spring JDBC 查询、更新与行映射
├── model
│   ├── dto             请求参数与业务命令
│   ├── vo              页面及 API 返回结果
│   └── po              数据库记录和查询投影
├── messaging           Outbox 投递与 Redis Stream 消费
├── recovery            到期关闭、重试、退款与候补扫描
├── security            登录身份、权限与请求过滤器
├── exception           统一异常响应
├── validation          请求字段与参数规则
├── config              启动守卫、数据源与运行组件
└── catalogue           目录缓存、缓存失效与参数规则
```

持久层实际使用 **Spring JDBC / JdbcTemplate + Repository**。SQL 位于 Repository，查询结果通过行映射得到 PO；业务服务调用具名数据访问方法，Controller 不直接访问数据库。核心业务接口由 `service/impl` 实现，事务边界、锁顺序和条件更新的业务含义由实现类负责。未使用的 MyBatis-Plus 依赖已经移除，因此这里没有 Mapper XML。

例如购票链路是 [BookingController](src/main/java/com/ticketing/booking/controller/BookingController.java) → [BookingService](src/main/java/com/ticketing/booking/service/BookingService.java) → [BookingServiceImpl](src/main/java/com/ticketing/booking/service/impl/BookingServiceImpl.java) → [BookingRepository](src/main/java/com/ticketing/booking/repository/BookingRepository.java)。严格校验后的参数形成 [CreateBookingDTO](src/main/java/com/ticketing/booking/model/dto/CreateBookingDTO.java)，响应使用 [BookingAcceptedVO](src/main/java/com/ticketing/booking/model/vo/BookingAcceptedVO.java) / [BookingStatusVO](src/main/java/com/ticketing/booking/model/vo/BookingStatusVO.java)，数据库记录类型见 [BookingRows](src/main/java/com/ticketing/booking/model/po/BookingRows.java)。DTO 不限于浏览器输入；它也用于把已经验证的命令传给业务服务。

## 源码学习路线

先从 [网页](src/main/resources/static) 或 [接口示例](docs/product-api.http) 找到请求，再按下表从业务接口追到实现、Repository 和返回对象：

| 顺序 | 调用入口与实现 | 数据访问 / 返回类型 | 可以跟着追的问题 |
| --- | --- | --- | --- |
| 1 | [AuthService](src/main/java/com/ticketing/booking/service/AuthService.java) → [AuthServiceImpl](src/main/java/com/ticketing/booking/service/impl/AuthServiceImpl.java) | [AuthRepository](src/main/java/com/ticketing/booking/repository/AuthRepository.java)、[AuthLoginVO](src/main/java/com/ticketing/booking/model/vo/AuthLoginVO.java) | 手机号如何发码、校验、创建用户和保存会话？ |
| 2 | [CatalogueService](src/main/java/com/ticketing/booking/service/CatalogueService.java) → [CatalogueServiceImpl](src/main/java/com/ticketing/booking/service/impl/CatalogueServiceImpl.java) | [CatalogueRepository](src/main/java/com/ticketing/booking/repository/CatalogueRepository.java)、[EventDetailVO](src/main/java/com/ticketing/booking/model/vo/EventDetailVO.java) | 草稿如何发布？[目录缓存](src/main/java/com/ticketing/booking/catalogue/CatalogueCache.java)与数据库事实如何区分？ |
| 3 | [BookingService](src/main/java/com/ticketing/booking/service/BookingService.java) → [BookingServiceImpl](src/main/java/com/ticketing/booking/service/impl/BookingServiceImpl.java) | [BookingRepository](src/main/java/com/ticketing/booking/repository/BookingRepository.java)、[BookingAcceptedVO](src/main/java/com/ticketing/booking/model/vo/BookingAcceptedVO.java) | 为什么受理请求先写 MySQL？[BookingStream](src/main/java/com/ticketing/booking/messaging/BookingStream.java)如何投递和认领 Outbox？ |
| 4 | [AllocationService](src/main/java/com/ticketing/booking/service/AllocationService.java) → [AllocationServiceImpl](src/main/java/com/ticketing/booking/service/impl/AllocationServiceImpl.java) | [AllocationRepository](src/main/java/com/ticketing/booking/repository/AllocationRepository.java)、[建表迁移](src/main/resources/db/migration) | 重复消费为什么不会重复出票？库存与用户资格靠什么约束？ |
| 5 | [PaymentService](src/main/java/com/ticketing/booking/service/PaymentService.java) → [PaymentServiceImpl](src/main/java/com/ticketing/booking/service/impl/PaymentServiceImpl.java) | [PaymentRepository](src/main/java/com/ticketing/booking/repository/PaymentRepository.java)、[PaymentVO](src/main/java/com/ticketing/booking/model/vo/PaymentVO.java) | 付款与超时关闭如何竞争？迟到结果如何处理？ |
| 6 | [OrderCloseService](src/main/java/com/ticketing/booking/service/OrderCloseService.java) → [OrderCloseServiceImpl](src/main/java/com/ticketing/booking/service/impl/OrderCloseServiceImpl.java) | [OrderRepository](src/main/java/com/ticketing/booking/repository/OrderRepository.java)、[OrderVO](src/main/java/com/ticketing/booking/model/vo/OrderVO.java) | 主动取消与到期扫描如何共用释放逻辑？为什么不会重复释放？ |
| 7 | [WaitlistService](src/main/java/com/ticketing/booking/service/WaitlistService.java) → [WaitlistServiceImpl](src/main/java/com/ticketing/booking/service/impl/WaitlistServiceImpl.java) | [WaitlistRepository](src/main/java/com/ticketing/booking/repository/WaitlistRepository.java)、[WaitlistVO](src/main/java/com/ticketing/booking/model/vo/WaitlistVO.java) | 候补如何按提交顺序补位、转移资格？ |
| 8 | [RefundService](src/main/java/com/ticketing/booking/service/RefundService.java) → [RefundServiceImpl](src/main/java/com/ticketing/booking/service/impl/RefundServiceImpl.java) | [RefundRepository](src/main/java/com/ticketing/booking/repository/RefundRepository.java)、[RefundVO](src/main/java/com/ticketing/booking/model/vo/RefundVO.java) | 退款何时释放库存？[恢复扫描](src/main/java/com/ticketing/booking/recovery)如何处理失败与重试？ |

最后结合 [测试目录](src/test/java/com/ticketing/booking) 查看幂等、事务回滚、故障恢复和 HTTP 字段兼容的具体断言。支付、退款和关单之间仍保留少量共享锁记录的兼容适配；优先阅读已明确类型的 DTO、VO 和 Repository 方法。

数据库迁移按 V1～V7 逐步演进，已应用的 SQL 不应直接改写；新增结构用新的迁移版本。网页接口返回的 ID 使用字符串，金额使用整数分；Redis 展示余量可能短暂滞后，交易是否成功最终以 MySQL 中的订单状态为准。

## 构建与测试

```powershell
# 不连接 MySQL / Redis 的离线测试
mvn "-Dtest=*OfflineTest" test

# 打包；只跳过测试执行，不跳过源码编译
mvn -DskipTests package
```

离线测试已改为从当前项目与运行类路径查找资料，不再要求旧作者目录或固定 Maven 缓存位置。首次下载 Maven 依赖仍需要联网；这里“离线”指测试不访问外部业务服务。已有完整依赖缓存时可额外使用 Maven 的 `-o`。

真实集成测试需要先按“快速启动”生成 `.env` 并启动独立 MySQL / Redis，同时停止正在运行的网站，让 `18082` 端口空闲。随后执行：

```powershell
# 测试会自行启动应用，连接并写入独立学习库，创建用户、活动、订单等测试数据。
pwsh -File scripts/test-learning.ps1

# Maven 不在 PATH 时指定可执行文件；已有缓存时可选指定缓存目录并启用离线构建。
pwsh -File scripts/test-learning.ps1 -MavenCommand 'C:\tools\apache-maven\bin\mvn.cmd' -MavenLocalRepository '.m2' -Offline
```

[test-learning.ps1](scripts/test-learning.ps1) 只把 `.env` 中三个应用密码临时放入当前进程环境，执行结束后恢复，不输出密码。它显式开启 `product.live` 与 `product.learning`，运行 `ProductCatalogueIntegrationTest`、`ProductFlowIntegrationTest`、`ProductRefundIntegrationTest`、`ProductRecoveryIntegrationTest`。测试入口锁定 `127.0.0.1:13316/ticketing_learning` 与 `127.0.0.1:16389`，启动后还会检查库名及 7 项成功迁移。测试记录会保留，脚本不会删库、清空数据或停止依赖服务。

旧本机入口、进程重启测试及私有夹具回归继续要求显式启用，依赖原开发环境，未纳入这条学习测试命令。不要把所有历史 `IntegrationTest` 默认指向朋友的数据库。

**重构前基线（2026-10-05）：** 全部 `*OfflineTest` 共 179 项通过，失败、错误、跳过均为 0；JAR 打包通过。该次数是结构重构前的记录，不代表本轮重构结果。

**本轮重构验证（2026-10-05）：** 全部 `*OfflineTest` **274 项通过**；四组学习环境真实集成测试 **31 项通过**，失败、错误、跳过均为 0。集成测试覆盖活动、购票、候补、付款、退款、事务回滚和管理员故障恢复。JAR 打包通过，并实际启动打包后的程序，验证首页中文内容与活动查询接口均返回 HTTP 200；测试夹具未被打进运行包。

上述真实集成验证使用独立本机 MySQL **8.0.46** 和 Memurai **4.2.3 / Redis API 7.4.9**。Compose 声明的 MySQL **8.4** / Redis **7.4** 容器配置已解析，但容器完整启动尚未实测。

学习模式的脱敏错误日志写入当前项目 `evidence/learning/application-errors.log`，不会依赖原开发机器的 `.local` 目录。日志只记录时间、预设错误分类和异常类型，不保存请求正文、验证码或凭据。

技术版本以 [pom.xml](pom.xml) 为准。主要代码使用 Spring Boot、Spring JDBC / MySQL、Flyway、Lettuce、Redis Stream 与 Redisson。

`.env`、`.local`、`.m2`、日志、构建产物和原开发证据均不提交。请保管自己的 `.env`，不要连同数据库备份公开上传。

原开发机器的运行方式、阶段记录及历史验证说明保存在 [历史开发记录](docs/history/local-development.md)，不作为新同学的启动步骤。项目由 AI 辅助开发，欢迎从调用链和测试开始理解业务取舍。
