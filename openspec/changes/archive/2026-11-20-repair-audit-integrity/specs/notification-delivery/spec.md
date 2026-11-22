## ADDED Requirements

### Requirement: 观察新鲜度的时钟边界
投递守卫 SHALL 将观察年龄与队列有效期分别按可比较的时钟判断。

#### Scenario: 回拨后重新确认
- **WHEN** 同次开机墙钟回拨，事件仍有效且监控刚确认在播
- **THEN** 不因回拨等待；重启后不直接比较两次开机的单调时间
