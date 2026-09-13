# 来现场 · 活动票务与候补平台

## 打开本地网站

在项目根目录双击 `启动票务.cmd`，或运行 `pwsh -File scripts/run-local.ps1 -Action Start`。
网站地址：<http://127.0.0.1:18082>。关闭时双击 `停止票务.cmd`；重复启动会返回当前状态，停止只关闭本应用。

首次从源码构建时运行 `pwsh -File scripts/run-local.ps1 -Action Start -Build`。沿用本机 JDK 17、MySQL 13306、Redis 16379 和 `.local` 中已有的票务专属凭据；脚本不会重装依赖或清空数据库。

手机号登录使用本地模拟短信。页面点击获取验证码后，双击 `查看验证码.cmd` 输入同一手机号。

取码窗口会持续等待输入；号码格式不对、尚未发码或验证码过期时会显示中文提示，修正后可直接重新输入。查询成功后把6位数字填回网页即可；输入 `Q` 并回车，或点击窗口右上角关闭按钮退出。

也可以直接查询一次：

```powershell
pwsh -File scripts/run-local.ps1 -Action Code -Phone 13900000000
```

验证码有效期为 2 分钟，60 秒内不能重复发送。默认本地管理员手机号为 `13900000000`；其他合法手机号首次登录会注册为普通用户。验证码只在本机日志与取码工具显示，HTTP 不返回验证码。本地管理员配置可设为空以禁用管理员登录。

首页已准备五个可购票的中文示例活动。需要在同一环境补回示例时，运行 `./scripts/seed-local-examples.ps1`；它通过正常管理员接口创建活动，遇到同名且已有场次的示例会跳过，保留原数据。售卖结束后的活动可在管理页面添加新场次。

## 已接入的使用链

- 普通用户：手机号登录 → 浏览活动与票档 → 提交购票申请 → 查询异步结果 → 本人待付款订单 → 模拟付款或取消。
- 票售罄时：主动加入候补 → 按已提交的入队顺序排队 → 名额释放后获得待付款订单 → 自行付款；候补不会自动扣款。
- 管理员：创建与修改活动、草稿场次和票档 → 发布 → 暂停或恢复受理 → 查看失败处理记录并填写原因重试。
- 后台：MySQL 持久请求与 Outbox、Redis Stream 投递/消费/认领、有限重试、订单到期释放、候补补位、缓存失效重试。

“已受理”只表示请求已保存；只有订单显示“已付款”才表示模拟付款已结算。每人每场次最多一个有效订单或候补资格，每次一张票。待付款限时 10 分钟（不晚于售卖截止）；已付款不能取消。本项目不接真实短信、真实资金、固定选座或已付款退款。

页面在同一应用中提供，无需另装前端工具或 Nginx。交易正确性由 MySQL 事务、条件更新和唯一约束保证；Redis 的展示余量可能短暂滞后，不决定交易是否成功。Stream 允许重复投递，业务处理保证同一来源不会重复创建订单；当前保留历史消息和失败证据，不自动截断未完成消息。

## 开发与验证

源代码在 `src/main/java`，网页在 `src/main/resources/static`，可执行包为 `target/ticketing-booking-0.0.1-SNAPSHOT.jar`。实际使用的 Spring Boot/JDBC/Flyway/Redis 客户端版本以 `pom.xml` 和本地依赖为准。

```powershell
# 编译与全部离线测试
./scripts/invoke-maven.ps1 -MavenArgs @('-o','-Dtest=*OfflineTest','test')
# 真实产品接口、目录缓存、消息、候补和管理恢复；网站须先停止以释放18082端口
./scripts/invoke-maven.ps1 -MavenArgs @('-o','-Dproduct.live=true','-Dtest=ProductCatalogueIntegrationTest,ProductFlowIntegrationTest,ProductRecoveryIntegrationTest','test')
# 原有付款/取消/事务并发回归，使用全新RunId及既有隔离IT库
./scripts/verify-biz04.ps1 -RunId 'your-fresh-run-id' -Stage Full -UseExistingIntegrationEnvironment
# 打包
./scripts/invoke-maven.ps1 -MavenArgs @('-o','-DskipTests','package')
```

产品测试在 `ticketing_biz01` 中通过真实管理接口建立新的测试数据，保留结果；原有回归使用 `ticketing_biz01_it` 及独立夹具。不会连接旧 hmdp 数据库。大量测试用户会触发真实验证码限流，夹具按 `Retry-After` 有界等待，不关闭限流或清除共享计数。

接口操作集合见 [product-api.http](docs/product-api.http)。执行证据保存在被 Git 忽略的 `evidence/`；原失败记录保留。源码和测试由 AI 辅助开发，运行验证不等于学习者已经掌握，也不代表生产环境性能或高可用保证。`.local`、`.m2`、日志和真实凭据不提交。

2026-09-13 接续开发的实际结果：17 项产品集成与 242 项原有回归均通过，失败、错误、跳过均为 0；最新 JAR 已构建并启动。真实浏览器完成管理员登录、创建活动/场次/票档并发布、普通用户异步购票、取消释放、第二用户候补补位及手动模拟付款。订单刷新后保留，两个测试标签页未捕获到控制台错误，390px 小屏首页和票夹无横向溢出。启动、重复启动、正常停止、重复停止及再次启动已实际验证。证据索引：[本次交付验证](evidence/product/resume-0913-delivery/verification.json)。

## 历史后端演示与证据

以下为此前受控后端演示的历史说明。它使用 18083 和测试身份，与上方 18082 的普通登录网页入口不同；其中“尚未实现”的描述仅代表当时版本。

## 本地演示入口

已补齐受控演示进程、启停脚本、自动取 ID 的五组交易 HTTP 场景。使用已安装的 **PowerShell 7（`pwsh`）**，示例运行编号每次新开演示时换一个：

```powershell
pwsh -File D:\Codex\ticketing\scripts\demo\Invoke-TicketingDemo.ps1 -Action Status -RunId demo-20260913a
pwsh -File D:\Codex\ticketing\scripts\demo\Invoke-TicketingDemo.ps1 -Action Start -RunId demo-20260913a
pwsh -File D:\Codex\ticketing\scripts\demo\Invoke-TicketingScenarios.ps1 -RunId demo-20260913a
pwsh -File D:\Codex\ticketing\scripts\demo\Invoke-TicketingDemo.ps1 -Action Stop -RunId demo-20260913a
```

Start 需要既有 MySQL 13306 / Redis 16379 可连接，绑定 `127.0.0.1:18083`。只提供真实后端测试接口，**不是网页前端、正式登录或真实支付**。Status 不会造数；Start 不修复 Docker、不建库、不接旧 hmdp 数据库；Stop 仅正常关闭可核对身份的本轮 Java 进程，保留数据和证据。固定运行编号不可在停止后重用，避免覆盖现场。

场景自动验证付款与幂等/权限拒绝、取消与重复取消、受控到期、迟到成功保护新订单、六次失败后的受权恢复；用户不必手填 token 或订单 ID。测试凭据仅在 `.local/demo/<runId>/`，不会打入正式 JAR。原全库扫描在 Spring 启动前禁用，演示只驱动服务端本轮清单；请求也不能用演示身份操作历史活动。

2026-09-13 已实际跑通：191 项回归（67 项真实集成 + 124 项离线）、1 项真实跨运行隔离全部通过；随后修复测试 classpath 末尾分隔符，最终 125 项离线测试及打包通过。`demo-live-0913a`、`demo-live-0913b` 两个全新进程各完成五组真实 HTTP/SQL 场景，重复启动、就绪查询、正常停止和重复停止通过，演示前旧数据核对不变。46 项脚本离线检查通过。MySQL/Redis 原容器保持 healthy，演示 Java 已正常停止，数据保留。

实际证据索引：[本轮验证结果](evidence/DEL-02-I-001/live-0913-final/verification.json)。组合脚本 `live-0913b` 的旧失败回执保留：它通过回归/隔离后遇到 classpath 分隔符问题；修复后直接跑完两轮新演示和最终打包，没有把旧回执改为成功。下列命令可从头复跑全部检查（使用全新编号，保留两轮演示数据）：

```powershell
pwsh -File D:\Codex\ticketing\scripts\verify-demo.ps1 -RunId demo-check-0913 -UseExistingIntegrationEnvironment
```

## 当前代码：支付异常恢复

已加入四个受控管理接口、V4 操作记录表、独立恢复代次及原结算流程的恢复处理。仅模拟支付，无真实资金；正式登录、活动目录/展示缓存、常驻网页、Stream、候补尚未实现。V1～V3 保留不改，V4 已在原隔离 IT 库执行，升级前后旧行和旧列值逐项一致。

2026-09-13 实测：17 组新增付款管理场景多次通过，包括真实 HTTP/SQL、并发、回滚、断连、新进程恢复、锁等待跨付款期限；最终整套回归结果见上方。普通 JAR 不含测试身份或演示实现，仍受启动守卫保护；已交付的是本地测试后端与演示脚本，不是完整常驻网站。

不依赖 Docker 的构建与测试（PowerShell 7）：

```powershell
& 'D:\Codex\ticketing\scripts\invoke-maven.ps1' -MavenArgs @('-o','-Dtest=*OfflineTest','test')
& 'D:\Codex\ticketing\scripts\invoke-maven.ps1' -MavenArgs @('-o','-DskipTests','package')
```

环境已启动后，用 `scripts/verify-biz04.ps1` 运行真实 HTTP、数据库和进程恢复测试；脚本不会安装或修复 Docker，不会连接旧 hmdp 数据库。管理入口默认关闭，只允许测试适配器识别的角色+能力以及服务端本轮清单中的记录。返回 202 是持久受理，不等于付款成功；同一幂等键可以查询或重试原操作。

下方保留 BIZ-03 的历史实现说明和复跑入口；涉及“未实施”的表述仅对应当时基线，当前状态以上方新结果为准，不沿用历史 50 项作为新功能成绩。

## BIZ-03 历史基线

当前 BIZ-03 / S3：本人 HOLD → 持久付款尝试 → 模拟结果 → PAID 或失败/迟到冲正 → 本人查询。**模拟支付，无真实资金**。只有隔离测试身份可运行，不是可上线平台；正式登录、前端、S4 授权人工恢复、S5、Stream/候补均未实施。

## 环境与复跑

沿用已验收 JDK17 / Boot4.1.1 / JDBC9.7.0 / Flyway12.4.0 / Lettuce7.5.2.RELEASE，不下载或选型。仅既有 ticketing_biz01_it、it app/migrator、127.0.0.1:13306/16379、VERIFY_CA；HTTP 仅127.0.0.1:18083。不运行 prepare、不使用 root、不连正式空库、不重建依赖。

PowerShell 7，选择从未使用的8～24位小写字母/数字/横线 runId：

```powershell
& 'D:\Codex\ticketing\scripts\verify-biz03.ps1' -RunId 'your-new-unique-run' -Stage Full
& 'D:\Codex\ticketing\scripts\audit-biz03.ps1' -RunId 'your-new-audit-run'
```

Full 固定50项：原有34项完整保留，新增 PaymentMigrationTest 1、PaymentSmokeTest 1、Q01～Q14 14。Migration/Smoke/Regression 可限定阶段。verify-biz01/02 兼容入口转交现行脚本，不向旧证据目录写入。审计脚本只读两个 IT 账号和本轮精确 Redis 清单，不访问正式库。

新证据仅 evidence/BIZ-03-I-001/<runId>/；场景分 -payments/-pay-smoke/-v3/-orders/-close/-contract/-smoke 子轮。已用 runId 拒绝。保存 HTTP 脱敏正文、独立 SQL、夹具/限流键 manifest、进程 PID、XML/退出码。成功夹具精确清理；失败记录保留。C07/S07/Q10 仅中断自己的 Java 子进程，不重启数据库、Redis、系统。

## 契约与主链

[增量契约](docs/biz03-contract.md)、[现行静态 OpenAPI](docs/biz03-openapi.json)、[验证摘要](docs/biz03-verification.md)。六条真实路由：原购票 POST/申请 GET/订单 GET/取消 POST；新增订单 payment-attempts POST/付款尝试 GET。没有公开故障、模拟结果、管理员恢复或在线文档端点。

付款只收 Idempotency-Key 与严格空 JSON，不收金额、用户、时间或结果。202 只表示 P0 受理，不表示付款成功；查询分别展示 resultStatus 与 settlementStatus。同键同单可在关闭/已付后定位原尝试，同键异单409；只有上一尝试 FAILED+NO_CHARGE 才允许新键。503/PAYMENT_ACCEPTANCE_UNKNOWN 必须沿原键重试或原 ID 查询，不换键赌结果。

P0 持久保存订单金额快照、固定计划及 current 关联；P1 一次生成权威结果与专用来源 Outbox；P2 校验来源/身份/金额/币种/摘要后结算。锁后 t<D 且仍有资格的当前 HOLD → PAID/APPLIED，不再扣票。失败 → NO_CHARGE。迟到成功 → 必要时先关闭 + 唯一等额 REVERSED，不复活、不影响新 owner。UNRESOLVED/REVIEW 不表示已付款或已冲正。

READ COMMITTED，统一场次→票档→资格→请求/订单→尝试→事件，全部需要的锁后独立数据库取时。P2 调用 C 已锁核心，不反向取锁。超期付款入口先提交 C 再返回409；PAID 取消409，扫描不释放。关闭终态/时间/原因固定，新增支付关联可推进；原申请 SUCCEEDED 不回退。库存公式 capacity-available=HOLD+PAID，每单一条 RESERVE、关闭一条 RELEASE。

P1/P2 各自有界数据库扫描，每批20，坏候选不长期挡住后续项；直调/补扫同处理器，不使用 Stream。固定计划最多延迟20分钟，生成期限30分钟；超期未生成固定 FAILED，已有结果不改。P2 技术失败最多6次，1/2/4/8/16秒加0～20%抖动，代次校验防旧失败覆盖成功；不受购票申请120秒截止影响。耗尽 DEAD/REVIEW 可查，C 仍独立到期释放。正式授权人工处理留 S4，本轮没有完整支付运维闭环。

## 启动与秘密边界

模拟器受测试 classpath + biz01-it + 显式 biz.simulation.enabled 三重限制，开关默认关闭；测试夹具由本地 harness 开启，无普通请求控制入口。Bootstrap 在迁移/HTTP/扫描前核对测试实现位置、profile、目标及身份。普通 JAR 不含测试实现，不能作为已交付常驻服务。

.local 原凭据和信任库只读沿用，不提交；旧 CA/Redis 密码仅既有精确路径读取。迁移池只局部用于 Flyway，业务 DAO 始终 app 账号。V1/V2/V3 一旦应用不能改写、repair、clean；后续修正仅新版本。MySQL 多语句 DDL 非整段原子，失败须保存现场，不擅自掩盖历史。

MP/Redisson/springdoc 仅沿用依赖，不声称已有 Mapper、Redis 订单锁或在线文档。受控时钟/故障注入不代表生产事故或性能压测。测试通过不代表用户已掌握，后续仍需能够解释关键业务和取舍。
