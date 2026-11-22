## Why

11 月 20 日审查在真库发现备注损失历史、盲区虚增时长、暂停前请求越代、恢复跳过仍覆盖数据等问题。既有绿色套件没有覆盖这些边界，且相同应用版本可覆盖更高版本数据库。

## What Changes

- 把审查样例纳入回归；修复监控、投递、历史统计和身份隔离，不改变匿名使用路径。
- 明确恢复执行与取消的前态、账号导入取消结果和名称刷新计数；更正引导、版本和 Lint 门禁。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `monitor-state`：提交请求代际与在播证据。
- `data-portability`：恢复任务及获准身份边界。
- `history-statistics`：区间覆盖与单主播日期。
- `streamer-management`：关注导入与名称跳过口径。
- `notification-delivery`：观察新鲜度不混用时钟。
- `background-runtime`：引导按实际前台服务类型解释限制。

## Impact

涉及 Room 仓储、监控批次、设置/统计界面、版本及测试。详见 `docs/reviews/2026-11-20/report.md`；原审查保留。
