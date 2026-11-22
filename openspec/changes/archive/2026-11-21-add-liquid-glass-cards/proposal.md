## Why
现有卡片开关调整半透明与整张壁纸的磨砂，不会画出 Dock 的折射、高光与亮边；需要另一个可选外观，而不是替换老设置。

## What Changes
增加默认关闭的「所有卡片使用 Dock 液态玻璃」。不开 Dock、使用普通导航或进入二级页时也可单独使用。

## Capabilities
### New Capabilities
无。
### Modified Capabilities
- `appearance-navigation`：页面卡片可复用 Dock 的局部材质。

## Impact
外观设置 DataStore、卡片容器与绘制层。业务备份和 Room 不变。
