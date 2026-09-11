# 票务申请、占位、未付款关闭与模拟付款

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

MP/Redisson/springdoc 仅沿用依赖，不声称已有 Mapper、Redis 订单锁或在线文档。受控时钟/故障注入不代表生产事故或性能压测。执行自验不代替总控验收，用户理解尚未验证。
