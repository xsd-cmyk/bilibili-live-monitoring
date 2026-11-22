## Why
一级标签当前瞬切，返回看不到目标页；Dock 虽已放大，但必须先停留才能拖动。这次用直接滑选和可逆页面转场替换旧交互。

## What Changes
- 悬浮栏按下即反馈，直接横拖预览，松手才导航，不再计长按时间。
- 标签按屏幕顺序动，子页进退分层；支持系统预测性返回与取消。

## Capabilities
### New Capabilities
无。
### Modified Capabilities
- `appearance-navigation`：修订拾起手势并增加页面转场及预测性返回。

## Impact
仅主界面导航、Dock、系统返回声明与相关测试；不变更监控、数据库、备份或用户玻璃参数。
