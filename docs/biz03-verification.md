# BIZ-03 / S3 自验摘要

日期：2026-09-12（Asia/Shanghai）。仅模拟支付，无真实资金。执行自验通过，等待总控验收；不是完整支付平台或运维闭环验收。

## 实际运行

| runId | 内容 | 实际结果 |
| --- | --- | --- |
| biz03-mig-20260912a | V2旧式事件夹具→V3实迁移及重复 | 1/1；迁移1，重复0 |
| biz03-smoke-20260912a | 真实HTTP付款三阶段 | 1/1 |
| biz03-reg-20260912a | 新测试第一次编译 | 编译失败，未运行测试；泛型类型声明错误 |
| biz03-reg-20260912b | Q01～Q14 | 13/14；Q14测试漏换用户身份，真实接口正确404 |
| biz03-reg-20260912c | 修正测试后新增回归 | 14/14，54夹具清理 |
| biz03-full-20260912a | 全套 | 50/50，失败/错误/跳过0，退出0 |
| biz03-full-20260912b | 全套第二轮 | 50/50，失败/错误/跳过0，退出0 |
| biz03-build-20260912a | 离线package、JAR内容与真实启动拒绝 | 构建0；两次JAR启动均预期拒绝退出1 |
| biz03-clean-20260912a | reg-b已通过场景精确清理 | 32组清理；失败Q14的21组保留 |
| biz03-audit-20260912a | 两IT账号只读审计、Redis/端口 | 758精确限流键均不存在，18082/18083无监听 |

每轮全套10份Surefire XML：原BookingRegression 19、Migration 2、OrderCloseRegression 9、OrderCloseSmoke 1、OrderContract 1、Smoke 1、StartupBoundary 1，合计旧34；新PaymentMigration 1、PaymentSmoke 1、PaymentRegression 14，合计新16。50是测试项数，不是50个独立业务功能。构建跳过测试不替代此前两轮真实测试。

## 最低验收映射

原始证据根：D:/Codex/ticketing/evidence/BIZ-03-I-001/。全回归付款场景在对应runId的-payments目录，HTTP见events.jsonl，SQL见各Q*-facts.json/rollback.json。

| 任务条目 | 实测证据入口 |
| --- | --- |
| 1 首个真实链路 | PaymentSmoke；-pay-smoke/P01-after-P0、after-P1、facts；HTTP状态、原申请/订单及静态契约 |
| 2 幂等、并发、在途、不可见提交 | Q01；before-commit；Q09原键恢复 |
| 3 失败后新键、权威结果不可改 | Q02；完整行重投前后相等，过生成期限也不改SUCCESS |
| 4 PAID不能取消/释放、超期P0先提交C | Q03；独立SQL见EXPIRED+RELEASE+零付款尝试 |
| 5 D-1ms/D/D+1ms、真锁等待 | Q04；blocked-before-database-clock。使用受控D，实际MySQL票档锁等待，取时语句仍真实执行 |
| 6 取消/付款/超时赢家 | Q05；真实重叠调用、屏障控制持锁先后，唯一释放/冲正 |
| 7 旧单不伤新跨票档owner | Q06；全事实重放前后相等、资格明确指向O2 |
| 8 重投/补扫、结果先落库但结算过期 | Q07；result-before-D及最终REVERSED |
| 9 关键写点回滚、提交后丢应答 | Q08八写点及独立连接SQL；Q09真实TCP重置与分别标注的应用ACK注入 |
| 10 真实退出后新PID恢复 | Q10-P1-committed、crash/recover进程日志、recovery-summary；过期补扫结果另见Q07 |
| 11 生成期限竞争、6次失败 | Q11；six-failures退避范围、DEAD/REVIEW+C释放、旧失败代次不得覆盖成功 |
| 12 安全/模拟开关/内部一致性 | Q12三重门及严格输入/身份；Q13三个可信持久数据矛盾→REVIEW，无自动修复 |
| 13 增量迁移/约束/六路由 | 首次PaymentMigration的回填行与旧数据；Q13十五项负约束；OrderContract六路由对照+PaymentSmoke/Q*动态状态schema |
| 14 两轮旧34+新16、JAR | 两轮各10份XML和execution.json；package-verification.json |

## 数据、范围与限制

V1=-576261309、V2=243374070保留；V3=2021319884唯一成功，11张表（10业务+Flyway）。实际写入仅IT库既有账户，迁移DAO隔离；未访问正式空库。本轮只添加V3，未repair/clean/删库表或改授权。

两轮成功夹具各自精确清理。reg-b保留失败Q14的21笔PAID/SUCCESS/APPLIED、21条RESERVE、42条两类型Outbox及其关联目录/用户；不是常驻业务样例。原BIZ01失败样例仍仅4用户、2活动、2场次、4票档，不删除。总残留：46用户、23活动、23场次、46票档；请求/订单/资格/流水/尝试各21，Outbox42。保留失败现场不是退款或回滚。最终核对实际清单见resource-audit.json及cleanup-manifest.json。

所有原始失败证据保留，没有把失败日志改成通过。两处实际发现的问题均在新增测试工程：泛型推断、跨夹具身份切换；其他INJECTED异常/可信数据篡改/进程退出都是受控实验，不称生产事故。并发实验非性能压测，受控时间非等待30分钟。

旧断言只为合法PAID/三个支付关联字段、typed Outbox与V3表数/历史、六路由及新证据目录升级；原幂等、C17关闭、S05新owner保护等34项完整保留。关闭终态/时间/原因仍固定；无付款变化时原整响应相等断言仍通过。

JAR不含fixture/TestRuntime/RecoveryProcess/私有凭据；默认及biz01-it+显式模拟开关真实启动均拒绝。应用/子进程结束，既有Docker依赖保持原running状态。Redis未增加库存、锁、Stream结构。

S4正式授权人工恢复、全面异常矩阵、S5交付、正式身份/登录、目录缓存、真实资金、Stream、候补未实施；DEAD/REVIEW可查但不声称正式人工处置闭环。用户掌握未验证，中央导师记录未修改。验收后下一任务仍须总控明确派发。
