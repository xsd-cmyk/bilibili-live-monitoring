## Why
上一版 Dock 只平移一块固定大小的选中底色，手指无法拿起玻璃并在标签间试选。

## What Changes
- 点击切页时让选中镜片沿移动方向伸展、追随并回弹；玻璃模式局部放大已绘制的栏体材质并有随动高光。
- 悬浮 Dock 支持长按横滑预览，松开提交一次；取消或栏外释放回到原页，普通贴底导航保持原样。

## Capabilities
### Modified Capabilities
- `appearance-navigation`：选中镜片的交互、手势取消与导航语义。

## Impact
仅改变 Dock 交互和版本号，不新增偏好、后台逻辑、数据库迁移或外部依赖。
