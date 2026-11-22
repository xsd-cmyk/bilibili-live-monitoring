## Why
现有悬浮栏窄，图标上下的留白点不到。长按沿用系统默认延迟，镜片又被栏体裁掉，用户很难察觉何时已经能够拖动。

## What Changes
- 扩大实际标签热区，让靠近栏体上下边缘的点击有效。
- 缩短普通长按等待；显式较长的系统长按时间仍优先。长按抬起透明镜片，略微超出栏体，不引入新的模糊。

## Capabilities
### New Capabilities
无。
### Modified Capabilities
- `appearance-navigation`：扩大悬浮 Dock 热区与镜片，缩短拾取等待。

## Impact
仅一级页导航、内容底部避让与相关测试；无依赖、设置键、监控或数据库变更。
