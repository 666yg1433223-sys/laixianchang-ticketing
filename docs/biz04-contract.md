# BIZ-04 增量契约：受权支付恢复（S4）

基线 d3d8f9854ff9765f22390b7eceb1272c03080827；沿 codex/biz01-first-slice。
状态：接口与实施约束已冻结，尚未编码、迁移或测试。2026-09-12 阶段断点；不表示 S4 已实现。
沿用 BIZ03 的 P0/P1/P2、C、身份隔离及金额/D 规则，仅模拟支付，无真实资金。

## 1. 四个内部入口

旧六路由与响应保持；当前目标契约见 biz04-openapi.json，新增四路由标记待实现。

| 方法与路径（均在 /v1/internal/sim 下） | 严格输入 | 输出 |
| --- | --- | --- |
| GET /payment-failures | afterAttemptId 默认字符串 0；limit 默认 20、1～50；无其他参数 | 200 items、nextAfterAttemptId；仅本轮 REVIEW/DEAD，按 attemptId 升序 |
| POST /payment-attempts/{attemptId}/recover | Idempotency-Key；JSON 仅 reason | 202 操作回执，只有持久受理，不承诺支付成功 |
| POST /payment-results/{resultId}/redeliver | Idempotency-Key；JSON 仅 reason、deliveries（整数 1～3） | READY：202；已 DONE：200 原结算回执；DEAD：409 拒绝回执 |
| GET /operations/{operationId} | 无 body/查询参数 | 200 持久操作回执，不触发新一轮恢复 |

业务 ID 为正 signed BIGINT 十进制字符串；generation 也是十进制字符串。结果/操作 ID 为小写 UUID。原因 trim 后 1～200 字符，不允许控制字符。幂等键沿用 8～64 位 [A-Za-z0-9._:-]。禁止重复 JSON 字段、尾随 JSON、多余字段/参数和重复查询参数；禁止 outcome/amount/userId/库存/状态/截止/SQL/callback 等控制输入。

401 UNAUTHENTICATED；403 SIM_MANAGEMENT_FORBIDDEN；404 RESOURCE_NOT_FOUND（越界与不存在统一）；400 INVALID_ARGUMENT；409 IDEMPOTENCY_CONFLICT。上述为旧 Error 结构。已授权、范围内、输入合法但业务拒绝，持久保存 REJECTED 回执并以 409 返回该回执（NOT_RECOVERABLE、RECOVERY_REQUIRED、TRUSTED_RESULT_INCONSISTENT）。503 DEPENDENCY_UNAVAILABLE / OPERATION_ACCEPTANCE_UNKNOWN：不能据此认为已受理或未受理，沿原键重试或查原操作，不换键。

同操作者+同键全局唯一，绑定 action、目标、规范化 reason、deliveries；同参返同 operationId 及当前状态（未完成202，完成200，拒绝409），异参409。新键对已结算记录提交 recover 返回拒绝，不重置终态；redeliver 可审计地返回原终态。两名有权操作者/不同键竞争同一 DEAD，业务锁串行检查，只有一次 generation 递增；落后者审计拒绝，不自动排队开启另一轮。

## 2. 身份与本轮服务器清单

入口过滤器在参数处理和业务查询前执行：可信 Bearer 身份、ADMIN 角色、SIM_TEST_OPERATOR 能力、唯一 biz01-it profile、已验证 target/test-classes 来源、显式 biz.sim.management.enabled=true。默认关闭；模拟支付开关本身不授予管理权。不接受 role/userId 请求头；测试身份适配器不进入普通 JAR。

普通原 token 只能识别为普通身份。额外随机操作员凭据仅在独立 .local/biz04-operators.json，权限限定当前用户/SYSTEM/管理员，不写日志/证据/Git。至少有两名完整授权操作者，以及只有角色、只有能力的负例。可信 operatorId 来自该适配器，不依赖前端传值。

每次操作先检查服务端当前 runId 的清单。按 attemptId 访问必须命中清单白名单；resultId/operationId 的定位查询必须同时限制在白名单 attemptId 内，不能先无范围读取再鉴权。分页亦加同一限制，不允许请求指定 runId、清单文件或任意 ID 列表。清单由服务器测试夹具持久保存，关联本轮目录/用户/尝试，跨新 PID 复用；任何客户端字段都不能把旧 21 笔现场加入清单。旧支付正常业务路径仍按本人身份校验。

拒绝/失败诊断仅记录固定错误码、时刻、受控边界及可用的可信操作员标识，不记录 Bearer、密码、原始 body 或任意异常信息；每 run 最多 200 条、每条 512 字符。诊断丢失不能替代持久审计；DB 失败不返回假202。

## 3. V4 与锁序

不修改 V1～V3。V4 仅新增 sim_payment_operation 一张窄审计表，并为 outbox_event 增 recovery_generation BIGINT NOT NULL DEFAULT 0、非负约束；BOOKING 事件保持 0，不参与支付恢复。应用表10→11，加 Flyway 共12。

审计字段：operation_id、operator_id、action、attempt_id（FK）、target_id、idempotency_key、reason、deliveries、规范化参数摘要、before_settlement/before_event、before_attempts/before_error、generation、accepted_at、completed_at、status（ACCEPTED/COMPLETED/REJECTED）、result/rejection_reason。唯一(operator_id,idempotency_key)；索引(attempt_id,generation,status)。原因/前状态/旧错误计数一经记录不覆盖。结果为 APPLIED/NO_CHARGE/REVERSED/REVIEW，拒绝结果为空；已接受但未完成时 completed/result 为空。非受理业务拒绝 accepted_at 为空、completed_at 为裁决时刻。

保持场次共享→票档→资格→请求/订单→attempt→typed Outbox→审计；不得先锁审计再取业务锁。单目标操作只锁其业务链；幂等预查可无锁，进入业务锁后再次确认，唯一冲突回滚后读取原记录处理。P2 与恢复、计次都以同一顺序锁定待完成审计行，再获取数据库裁决时间，避免审计锁等待沿用旧时间。

V4 前保存十张业务表及 Flyway 的独立只读快照；核对现存21 PAID、42两类 DONE、原目录样例不变。升级比较旧列逐值一致、新 generation 全0、审计空表；校验 V1=-576261309、V2=243374070、V3=2021319884；第二次迁移0。不用修复历史或删库建库达到通过。

## 4. 原子恢复与旧代次隔离

受控恢复事务复用 P2 的关联、摘要、金额/币种、请求、当前 HOLD/资格/流水检查；允许检查 REVIEW，不能绕过可信事实。只有 REVIEW+DEAD、未结算且一致才写 ACCEPTED 审计，与 generation+1、attempt REVIEW→UNRESOLVED、Outbox DEAD→READY/attempts=0/next=锁后当前时间同事务提交；保留旧错误/次数到审计，清除新轮临时错误。结果ID/摘要/结果/金额/D 永不改。

没有新增结算实现：原 PaymentRecoveryScanner 调同一 PaymentService.settle。P2 的无锁候选观察、锁后执行、失败计次都携带独立 generation；锁后不相同即退出，不能把旧执行者升级为新代次。当前轮内部仍比较 attempts/due 防止迟到计次。每轮最多6次自动尝试，到 DEAD/REVIEW 后只能再次人工明确受理，不自动续轮。

P2 终态写入/Outbox DONE 或再次 DEAD，与当前 generation 的 ACCEPTED 审计 COMPLETED/result 同事务提交；各写点失败均回滚。持久 READY 就是恢复工作，不另造通用作业框架。进程在恢复提交后退出，新 PID 补扫该 READY，P2 与审计一起收敛；不会只有202却没有任务。提交应答丢失，同键读到原操作；查询不产生新恢复代次。

人工权限不更改裁决：t_dec<D 且当前 HOLD/事实一致 SUCCESS→APPLIED；迟到/关闭 SUCCESS→必要 C+REVERSED；FAILED→NO_CHARGE（若需到期仍走C）。APPLIED/NO_CHARGE/REVERSED 不能复位；关闭历史不复活、不清掉跨档 O2 的 owner。C 独立工作，DEAD/REVIEW 不阻止到期释放。摘要/金额等已被夹具破坏时管理端仍拒绝并审计；只有夹具恢复原始可信值后才允许受理。

## 5. 安全重投

按既有 resultId 定位权威行，输入不包含结果内容。DONE 对应终态直接审计并返回原结算，无新结果/冲正/释放。READY 写入当前 generation 的操作审计后最多触发 deliveries 次同一 P2，每次仍遵守自动 next/attempts；不是跳过退避或清零次数。遇终态立即停止，所以 deliveries 是上限而非保证次数。进程中断后原 READY 仍由既有补扫完成；操作审计跟随真实终态，不要求重做已经发生的投递次数。DEAD 重投记拒绝，只能走 recover。

## 6. 验证与交接边界

先 V4 快照/迁移，再真实 HTTP 六次失败→受权恢复→查询终态，之后竞争/中断；全部现行50项保留，加实际新增项，两轮唯一 runId 全回归；实际 Maven 退出码独立保存。待实施矩阵见 biz04-gap-matrix.md，不预填新增测试数量。

所有新证据只写 evidence/BIZ-04-I-001/<唯一runId>/；旧 evidence 和中央记录只读。成功夹具按本轮精确 manifest 清理，先删除其审计 FK，再清理付款链；失败现场保留。既有21笔 PAID 与旧目录、原凭据不得改。阶段保存、最终本地提交及回执后等总控验收。无 S5、正式身份/后台、目录发布、Stream/候补、真实支付/主动退票，也无多机或吞吐能力声明。
