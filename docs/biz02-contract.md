# BIZ-02 增量契约：未付款关闭
范围：沿已验收 BIZ-01，仅本人取消与到期释放；不实现支付/候补。最新产品决定和 BIZ-02 任务单优先。本文件为同任务实施断点，不是重做总设计。

## 1. HTTP 增量
- GET /v1/orders/{id}：本人查询，200 返回 Order；查询本身不关闭订单。
- POST /v1/orders/{id}/cancel：Content-Type application/json，必需请求体严格为 JSON 空对象 {}；不接受空体、null、数组、重复字段、尾随 JSON 或任何业务字段；不要求新幂等键，原订单 ID 即重试定位。
- 取消不接受URL查询参数；不支持的Content-Type返回400，不误报数据库503。
- 身份校验先于参数解析；ID 为无前导零的正十进制字符串，最大 Long.MAX_VALUE；401/400/404（别人和不存在一致）。数据库故障503；取消处理/提交确认异常为 CLOSE_RESULT_UNKNOWN，提示查询本人订单或按原订单重试，不能当作确定回滚。
- Order 固定字段：orderId/requestId/sessionId/tierId（字符串），orderStatus（HOLD/CANCELLED/EXPIRED），unitPriceCent/quantity/payableCent（整数分/1），currency=CNY，holdCreatedAt/payExpiresAt/closedAt（UTC毫秒，closedAt可null），closeReason（null/USER_CANCELLED/PAYMENT_DEADLINE_EXCEEDED）。不添加动态 serverTime，终态重试整个 Order 一致。
- 申请 GET 仍返回 SUCCEEDED 历史状态；内嵌 order 在既有字段上增加 closedAt/closeReason，实时反映关闭终态。原申请幂等键永久指向原申请；重新占位使用新键。

## 2. V2 与关闭事务
V1 不改。V2 仅扩展 booking_order 状态及 closed_at/close_reason，并将 inventory_ledger 检查扩展为 RESERVE(-1)/RELEASE(+1)，保留每订单每动作唯一和 BOOKING_REQUESTED-only 来源约束。HOLD 两关闭字段必须null；CANCELLED 两字段非空且理由USER_CANCELLED、hold_created_at<=closed_at<pay_expires_at；EXPIRED 理由PAYMENT_DEADLINE_EXCEEDED且closed_at>=pay_expires_at。历史HOLD自然兼容。MySQL多条DDL不是整段原子事务，失败停下保留证据，不repair或改已应用版本。

READ COMMITTED：无锁预读仅定位→场次共享锁→票档排他锁→用户场次资格→原申请→原订单；锁内复核路由/本人。全部业务锁后独立数据库取时t。已关闭先返回持久终态，不校验或清理后来新owner。HOLD须核对原申请SUCCEEDED、RESERVE恰一/RELEASE零、资格ORDER且owner为本单，异常回滚诊断。

同一事务：订单终态/时间/原因→库存+1且不越capacity→仅匹配原owner的资格NONE→唯一RELEASE；每个更新恰一行。本人取消t<D为CANCELLED，t>=D为EXPIRED；扫描t<D保持HOLD。暂停/停售不阻止关闭。公共事务内关闭方法支持未来合法复用，但本轮不添加支付框架。

## 3. 扫描与限制
新增到期扫描无锁取候选ID，每批<=20，逐单同关闭事务；1秒周期，原SQL/锁/网络超时。冻结每趟最大ID并用有界ID游标跨批推进，坏单本轮失败继续下一单，下一趟重试；脱敏诊断，不自动修复不一致。原申请扫描补同类游标与诊断，不更改T1/T2语义。
申请分配不反向关闭跨票档旧单；未被扫描的过期HOLD可暂时占资格。到期取消同步关闭，扫描后新键可再约。关闭历史保留，库存按当前HOLD与账本净额计算，非历史SUCCEEDED总数。

## 4. 验证映射
|编号|实测目标|
|---|---|
|S01|真实HTTP取消、订单/申请查询一致、资格NONE/归还/各一流水|
|S02|顺序与屏障并发取消、固定终态快照|
|S03|D-1ms/D/D+1ms、未到期扫描、真实锁等待跨D|
|S04|取消/扫描及双扫描竞争、另一用户取得归还票|
|S05|O1关闭→同用户跨票档O2→旧取消/重复T2/扫描不伤O2，原键历史|
|S06|四写入点回滚独立SQL、真实HTTP提交后响应丢失、原单查/重试|
|S07|HOLD持久后真实子进程退出、新PID到期扫描关闭|
|S08|新增路由身份/归属/严格空对象/ID/不可篡改字段、静态OpenAPI与HTTP一致|
|S09|暂停/停售仍关闭、坏单不阻塞/不饿死后续且可诊断|
|M01|旧目录数据/V1校验和不变、V2和重启0、受限DDL/启动拒绝|
|R01|原C01-C19保留；C17升级最终EXPIRED和一次释放，公共断言分历史/当前占用|

各阶段使用新的唯一runId，证据仅 evidence/BIZ-02-I-001/<runId>。完整套件完成后另一runId复跑；成功夹具按manifest精确清理，失败现场及V1/V2保留，不覆盖BIZ-01证据。具体实测结论另存阶段报告，不在此预填通过。
