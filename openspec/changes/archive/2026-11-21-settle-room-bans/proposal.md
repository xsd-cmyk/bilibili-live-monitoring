## Why
已确认封禁后监控跳过常规巡检，但旧路径只写标记，已开的场次可能一直显示进行中。用户需要区分一次封禁与对同一封禁的多次复查。

## What Changes
- 在确认封禁时结算未结束场次，不另发普通关播通知。
- 直播历史显示按主播的累计封禁数、明细及场次结束原因；只补录有证据的旧封禁。

## Capabilities
### Modified Capabilities
- `monitor-state`：确认封禁与场次结算。
- `history-statistics`：封禁事件的计数和展示。

## Impact
涉及监控事务、Room 升级、添加主播和直播历史。沿用既有备份格式。
