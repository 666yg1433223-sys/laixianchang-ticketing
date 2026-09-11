# BIZ-03 增量契约（S3，模拟支付，无真实资金）

基线 ffda76f；保留 BIZ01/02 已验收约束。仅隔离 IT 库，V1/V2 不改；S4 授权人工恢复、S5、真实资金、Stream/候补不在范围。

## 1. HTTP 与状态

POST /v1/orders/{id}/payment-attempts：身份先行、本人、Idempotency-Key 为 8～64 位既有限定字符，严格空 JSON，不收查询参数。202 仅表示 P0 持久受理，返回 attemptId/orderId/statusUrl/simulated/message。GET /v1/payment-attempts/{id} 联合校验本人及订单身份，返回结果和结算两个状态、整数分金额、UTC 毫秒时间及订单状态。业务 ID 均为十进制字符串。订单及原申请查询增加 currentAttemptId/paidAttemptId/paidAt；原申请 SUCCEEDED 不回退。关闭时间/原因和终态固定，支付关联可随结算变化。

400 INVALID_ARGUMENT；401 UNAUTHENTICATED；404 RESOURCE_NOT_FOUND；409 IDEMPOTENCY_CONFLICT / PAYMENT_IN_PROGRESS / ORDER_NOT_PAYABLE / PAYMENT_DEADLINE_EXCEEDED / PAID_CANCEL_DISABLED；503 SIMULATOR_DISABLED / PAYMENT_ACCEPTANCE_UNKNOWN。未知提交必须沿原键重试或原 ID 查询，不建议换键。同用户同键同订单优先返回原记录，即使已关闭或已付；同键异单冲突。新键只允许未到 D 的 HOLD 且此前无尝试或最后一笔 FAILED+NO_CHARGE。

## 2. V3 数据与关联

payment_attempt 是唯一新增业务表：订单/用户/场次、幂等键、attempt_no、金额币种快照；计划结果/计划生成时间/生成期限；result_status、result_id、result_at、result_reason、result_digest；settlement_status、settled_at、review_reason；compensation_id/cent/at。唯一约束覆盖用户键、订单序号、结果号、冲正号及条件生成列 APPLIED 对应订单。FK 使用订单已有身份组合。反向 current/paid 关联由同一事务和来源检查维护，不建立循环 FK。

PENDING 必须无权威结果且 UNRESOLVED；SUCCESS/FAILED 必须有完整权威字段。APPLIED 仅 SUCCESS；NO_CHARGE 仅 FAILED；REVERSED 仅 SUCCESS 且唯一等额冲正完整；REVIEW 保留结果并有诊断原因，不声称已结算。HOLD/关闭订单无 paid 字段；PAID 必须 current=paid、paidAt<D 且无关闭字段。

Outbox 增 booking_request_id/payment_attempt_id：先回填旧 BOOKING_REQUESTED，再加各自 FK+类型与 aggregate 相等 CHECK，最后删除旧单一 FK/CHECK。保留 type+aggregate 唯一。旧 T1 同步写新来源。付款事件仅由付款扫描器消费。

## 3. 事务与恢复

统一 READ COMMITTED，场次共享→票档更新→资格→原请求/订单→尝试→Outbox，全部需要的锁后独立查数据库时间。C 拆为取锁包装与已锁核心，P0/P2 不反向重入取锁。每个更新检查影响行数，失败全回滚。

P0：持久固定计划、PENDING/UNRESOLVED 和 current 关联；默认短延迟 SUCCESS，测试夹具可指定 FAILED 或至多 20 分钟延迟，生成期限 30 分钟。超期入口调用 C 后提交，再于事务外返回 409。

P1：到期计划或生成期限与正常生成竞争同一行锁；从 PENDING 一次生成不可变结果和 PAYMENT_RESULT_RECORDED，同事务；不改订单库存。生成期限胜出则 FAILED/SIM_GENERATION_EXPIRED，已有结果不改。

P2：读取数据库结果，校验身份/金额/币种/SHA-256 摘要。SUCCESS + 当前 HOLD + 正确资格 + 锁后 t<D → PAID/APPLIED/DONE，无二次扣票。FAILED → NO_CHARGE，过期 HOLD 同事务 C。迟到 SUCCESS → C（必要时）+唯一等额 REVERSED+DONE，关闭历史不碰新 owner。旧终态重投无副作用。内部不一致诊断并 REVIEW。

P1/P2 各自上界游标、每批 20 条、候选无锁、逐笔上述锁序；直调与补扫共用处理器。P2 技术异常独立事务以事件 attempts/nextAttemptAt 代次校验计数，最多 6 次，1/2/4/8/16 秒及 0～20% 抖动，耗尽 DEAD/REVIEW；不用申请 120 秒期限。C 独立释放未付 HOLD；PAID 不释放且不可主动取消。

模拟器三重门：测试 classpath、biz01-it、显式 biz.simulation.enabled；测试实现不进普通 JAR。不开放故障/伪造结果/管理恢复 HTTP。

## 4. 验证与证据映射

阶段① V3 实迁移/旧 Outbox 回填/重复 0/历史 checksum 与数据/负约束；②真实 HTTP HOLD→P0→P1→P2→PAID 与关联账本；③幂等并发、失败重试、D±1ms/真锁跨 D、关闭竞态/跨档新 owner、重复恢复、逐写点回滚与丢应答、真实新 PID 恢复、30 分钟边界/六次失败/内部不一致/安全门；④旧 34 项及新增测试两轮独立 runId、六路由静态契约、JAR 检查、精确 manifest 清理、保护哈希/提交/回执。

库存不变量 capacity-available=HOLD+PAID；每订单一条 RESERVE，关闭恰一条 RELEASE，活动订单零 RELEASE；请求历史状态不被付款改写。预期故障注入与实际发现问题分开记录。所有新证据只进 BIZ-03-I-001。
