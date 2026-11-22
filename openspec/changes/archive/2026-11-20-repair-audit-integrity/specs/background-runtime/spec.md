## ADDED Requirements

### Requirement: 按实际服务类型解释后台限制
首次引导 SHALL 说明本应用的 `specialUse` 前台服务可能被用户、系统或厂商停止，而不把其他服务类型的固定配额套用到监控上。

#### Scenario: Android 15 用户选择常驻通知
- **WHEN** 用户在引导中查看常驻通知方案
- **THEN** 说明系统与省电策略的限制，不宣称本服务六小时必停，也不承诺可以永久运行
