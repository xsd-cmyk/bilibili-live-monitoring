## Why
当前 Dock 会在全屏画布和栏内分别重绘页面，加重滚动和切换时的图形负担；均匀白边与整块蒙版缺少玻璃的厚度。

## What Changes
- 将实时背景滤镜限定在 Dock 大小，增加随底图变化的边缘折射与方向性高光，保留现有参数与系统版本回退。
- 精简页面切换和选中态动画，减少首页列表无关状态更新时的计算。

## Capabilities
### Modified Capabilities
- `appearance-navigation`：限定液态玻璃绘制范围、动画和降级行为。

## Impact
涉及 Compose 绘制、导航和首页展示层；不改变业务库、监控、备份或外观偏好格式。
