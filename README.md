# BIZ-01 申请、占位、本人查询

本目录为唯一业务实现，采用已验收组合；仅隔离测试身份可运行，不是完整购票平台。业务状态HOLD不等于付款；支付、取消、到期释放、候补、正式登录均未实现。不要作为正式服务上线。

## 运行已规定验证

已有依赖复用127.0.0.1:13306/16379，不安装或新建容器。两库及四账号已由BIZ-01-I-001限定授权创建；不要重跑prepare-biz01.ps1重置凭据。正式ticketing_biz01保持空库；真实迁移和测试仅ticketing_biz01_it。

PowerShell 7，选从未用过的8～24位小写字母/数字/横线runId：

```powershell
& 'D:\Codex\ticketing\scripts\verify-biz01.ps1' -RunId 'your-new-unique-run'
```

运行器包括19个动态业务场景、2个迁移/守卫测试、1个实际负向启动测试、1个首链冒烟测试，共23项；每轮报告、HTTP/数据库快照、资源清理manifest、进程PID/退出点都在evidence/BIZ-01-I-001/<runId>/（冒烟子轮为<runId>-smoke）。既有失败轮保留，不以重复使用旧runId覆盖。时钟/身份/中断注入都只在测试源码，HTTP仅18083；无公开测试控制接口。C07会真实退出自己的Java子进程并重新启动；不会停止数据库/Redis或重启电脑。

测试成功轮按manifest精确删除自有夹具与限流键；保留V1/历史及空业务表。失败现场保留，不能自动删库、repair或改已应用V1。测试夹具清理不等于取消/释放业务。

## 代码主链

IdentityFilter可信测试principal → BookingController严格参数 → BookingService的T1（申请+Outbox）→ 提交后AllocationService的T2（库存/HOLD/资格/RESERVE/终态）；RecoveryScanner调用同一个T2。本人GET以一次LEFT JOIN读取一致结果。

首切片用Spring JdbcTemplate表达显式锁SQL和TransactionTemplate边界。MyBatis-Plus、Redisson、springdoc依赖坐标沿E0保留，但本切片没有业务Mapper、Redis库存/Redisson订单锁或额外文档HTTP端点；不能把依赖存在当成这些功能已开发。核心版本Boot4.1.1、JDK17、MySQL JDBC9.7.0、Flyway12.4.0、Lettuce7.5.2.RELEASE；不再选型。

## 启动及凭据保护

Bootstrap在HTTP/扫描/迁移前校验唯一biz01-it profile、回环端口、库和账号、测试classpath及身份。迁移账号仅局部迁移连接，validateOnMigrate检查历史、执行待应用V1后完整validate；业务DAO只拿app账号。所有连接VERIFY_CA、会话UTC、短锁/网络超时。

.local中的新凭据/信任库仅当前用户、SYSTEM、管理员；旧CA与Redis密码通过任务单精确路径只读复用，未复制旧密码。使用新根独立.m2，不复制E0缓存；Maven官方缺失包的HTTPS补取保留官方校验和记录。不全局改代理、证书或环境变量。

默认/正式profile、错误库/账号/端口、缺测试身份均拒绝启动；普通打包JAR不含测试身份实现，不能直接启动biz01-it。此版本的目标是可复跑的隔离业务验证，不是常驻站点。

最终运行结果、Git与已知限制见[验证摘要](docs/biz01-verification.md)。总控验收前不推进支付或候补；用户理解尚未验证。
