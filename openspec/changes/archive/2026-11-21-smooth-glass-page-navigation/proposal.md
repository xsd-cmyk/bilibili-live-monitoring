## Why
一级标签接力淡化有露底帧，子页预测返回的底栏缺失，全卡片取样放大了滚动和切换的绘制成本。

## What Changes
- 标签切换保持内容连续；预览与取消时上一页携带对应的底栏。
- 降低全液态玻璃的重复绘制，保留透明折射、文字清晰和零模糊。

## Capabilities
### Modified Capabilities
- `appearance-navigation`：补充导航中间帧与全玻璃验收。

## Impact
仅应用导航、绘制与回归测试。数据库与外观设置格式不变。
