---
description: docs/sql 增量脚本必须幂等——禁止 DROP TABLE、裸 ALTER、无守卫 INSERT
globs: docs/sql/*.sql
scope: "tool:edit(docs/sql/*.sql), tool:write(docs/sql/*.sql)"
condition: "CREATE TABLE|ALTER TABLE|DROP TABLE|INSERT INTO|UPDATE |DELETE FROM"
interruptMode: tool-only
---

# docs/sql 增量脚本幂等性要求（qihang-oms）

此目录下的脚本会在**用户每次启动时全量重复执行**，必须满足：

1. **建表**：用 `CREATE TABLE IF NOT EXISTS`；**禁止** `DROP TABLE`（会清用户数据）。
2. **加列/改索引**：先判断存在性再 ALTER——照抄 `market_intel_migration.sql` 的 `mi_add_column` / `mi_add_index` 存储过程守卫；**禁止**裸 `ALTER TABLE ADD COLUMN`（第二次启动即报错，用户起不来）。
3. **插菜单/字典**：用 `INSERT ... SELECT ... WHERE NOT EXISTS` 守卫——照抄 `ai_image.sql` / `ensure-login-config.sql`。
4. **改存量数据**：用带条件的 `UPDATE`，只命中确定的旧值——照抄 `reset-default-admin-password.sql`；禁止无条件 UPDATE / DELETE。
5. 新脚本是唯一分发途径：`Build-Package.ps1` 自动全量拷贝本目录，无需改任何打包/启动脚本。
6. 自我验证：同一脚本连跑两遍必须无错（打包门禁 Test-FreshDatabase 会替你执行这一点）。
