# 票务申请、占位与未付款关闭

当前为 BIZ-02 增量：本人取消或到期时原子归还一张票并释放资格，可用新申请键重新占位。只有隔离测试身份可运行，尚无支付、候补、正式登录或前端；不是可上线的完整票务平台。

## 已有环境与复跑

沿用已验收 JDK17 / Boot4.1.1 / MySQL JDBC9.7.0 / Flyway12.4.0 / Lettuce7.5.2.RELEASE 组合，复用回环13306/16379。不重新安装、建库/账号或运行prepare-biz01.ps1。正式ticketing_biz01仍0表且profile禁用，真实迁移和测试仅ticketing_biz01_it。

使用 PowerShell 7，选择未使用过的8～24位小写字母/数字/横线runId：

```powershell
& 'D:\Codex\ticketing\scripts\verify-biz02.ps1' -RunId 'your-new-unique-run' -Stage Full
```

现行整套34项：原C01～C19（C17已升级到期释放），迁移/权限2、原冒烟1、实际启动保护1、新S01～S09、取消冒烟1、静态契约实HTTP/SQL对照1。回归使用现有本地依赖离线运行；无下载、系统配置或容器重建。旧verify-biz01入口转交此整套验证，不能生成新结果覆盖BIZ-01历史。

所有新证据仅在evidence/BIZ-02-I-001/<runId>/；订单场景、取消冒烟、接口契约、原冒烟分别使用-orders/-close/-contract/-smoke子轮。runId已用则拒绝。每轮保存HTTP脱敏正文、SQL快照、夹具/Redis精确清单、进程PID与退出点、XML及退出码。成功按manifest精确清理，失败保留；不DROP/TRUNCATE、FLUSH或改已应用V1/V2。C07及S07会中断自己的Java测试子进程后恢复，不停止数据库/Redis/电脑。

可由维护者在测试结束后做只读残留核对（另选新runId）：

```powershell
& 'D:\Codex\ticketing\scripts\audit-biz02.ps1' -RunId 'your-new-audit-run'
```

该脚本用四个原受限账号核对两库授权/表数/TLS，读取本任务精确Redis名单并EXISTS，无数据修改。正式账号连接仅用于只读核对空库，不启动正式应用。

## 当前契约与主链

[增量契约](docs/biz02-contract.md)、[静态OpenAPI](docs/biz02-openapi.json)、[验证摘要](docs/biz02-verification.md)。共四个真实业务路由：申请POST/本人申请GET/本人订单GET/本人订单cancel POST；没有未鉴权在线文档或故障控制路由。

取消请求Content-Type application/json且严格为{}，不接受身份/价格/时间/状态字段或URL查询参数。HOLD在锁后数据库时刻严格早于截止则CANCELLED，等于或晚于截止则EXPIRED；已关闭返回原关闭时间和原因。临时关闭确认异常503/CLOSE_RESULT_UNKNOWN意味着应查询/原单重试，不意味着确定取消失败。

关闭服务统一锁序：场次共享→票档排他→用户场次资格→原申请/订单，锁后取时；一个事务完成订单终态、库存+1、匹配owner的资格释放与唯一RELEASE。旧关闭单先返回终态，不误清除新订单owner；原申请仍SUCCEEDED，原幂等键永久定位旧申请，重新占位需新键。库存按当前HOLD与账本净额核对，不按历史SUCCEEDED总数。

到期扫描逐单复用关闭事务，每批最多20、默认1秒；冻结每趟上限的ID游标使坏单不会永久挡住后续项，失败保留HOLD并脱敏诊断，下趟再试。暂停/停售不挡旧单关闭。申请分配不会持新票档锁反向关闭旧票档订单：未被扫描的过期HOLD可暂占资格，GET不触发关闭；取消入口可同步判过期关闭。

## 启动、秘密与恢复边界

身份/时间/具体故障实现仅在测试classpath且biz01-it双条件启用。Bootstrap在HTTP/扫描/迁移之前核对profile、目标/端口/账号及身份；程序化迁移仅用局部迁移池，业务DAO只持app账号，所有连接VERIFY_CA。普通JAR不含测试实现，biz01-it及正式biz01不能直接运行；不是常驻服务交付。

.local沿用原私有凭据/信任库，仅当前用户/SYSTEM/管理员；旧CA/Redis密码只读，不复制或更改。私有目录、缓存、target、原始evidence不提交。显式SQL与TransactionTemplate实现沿用不重构；MP/Redisson/springdoc保留依赖不冒称已有Mapper/Redis订单锁/在线文档功能。

V1/V2不可改写或repair；MySQL多语句DDL不保证整段回滚。应用进程中断恢复已实测，不等于数据库丢失可恢复。owner/账本矛盾不会自动修数据，需限定范围排查。版本回退不能靠git退回V1后忽略已应用V2；若后续需要修正，须另批向前迁移和兼容验证。

本任务自验后等待总控验收，不继续支付/候补。用户掌握未验证。
