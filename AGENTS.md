# 启航OMS（qihang-oms）项目速览

Vue2 + Element UI 前端 / Spring Boot 多模块后端 / Python 市场情报 sidecar 的电商 OMS。
发版形态是 **Windows 桌面端 + Setup.exe**：内置 MySQL/Redis/JRE/Python；双击 `QihangOMS.exe` 启动全部服务，关闭窗口停止全部服务。推荐 `QihangOMS-Setup-*.exe` 装到纯英文路径（详见 `package/windows/README.txt`）。

## 模块地图

| 目录 | 说明 |
|---|---|
| `vue2/` | 前端；构建产物嵌入 jar 的 `api/src/main/resources/static/`，由 jar 直接服务（`HomeController` → `/index.html`），无独立 nginx |
| `api/` `service/` `model/` `mapper/` `core/` | Maven 多模块后端，入口 `ApiApplication`，打包产物 `app/oms.jar` |
| `intel-sidecar/` | 市场情报采集 FastAPI sidecar，打包运行于 `runtime\python` |
| `package/windows/` | 发版脚本；`installer/Host.cs` + `QihangOMS.csproj` 为 WebView2 桌面端（打开即启动并在窗口内打开系统，关闭即停止） |
| `docs/sql/` | **增量脚本唯一入口**（每次启动全量幂等执行） |
| `docs/qihang-oms.sql` | 首装基线快照（仅空库首装导入一次） |

## 发版链路关键文件

| 文件 | 职责 |
|---|---|
| `Start-QihangOms.ps1` | 启动；遍历 `sql\*.sql` 全量幂等同步（空库才导 base-schema） |
| `Build-Package.ps1` | 一键打包：npm build → 嵌入 jar → mvn → 组装 → QihangOMS.exe → BUILD-INFO(SHA256) → 首装门禁 → zip + Setup.exe |
| `Compile-NativeExes.ps1` | `dotnet publish` 编 QihangOMS.exe（WebView2）+ csc 编 Start/Stop/Setup 外壳 |
| `Test-FreshDatabase.ps1` | 首装模拟+机器断言（发版门禁，`-MysqlRoot` 可传模板路径） |
| `New-SanitizedSchema.ps1` | 快照 → `base-schema.sql`（只留 DDL + sys_menu/sys_dict_type/sys_dict_data/sys_task 种子 + 登录种子，**剔除一切用户数据**） |

## 标准发版流程

```
改代码（+数据库变更则往 docs/sql/ 加幂等脚本）→ git commit
powershell -ExecutionPolicy Bypass -File package\windows\Build-Package.ps1
→ package\out\QihangOMS-Setup-<时间>-<git短哈希>.exe
→ package\out\QihangOMS-<时间>-<git短哈希>.zip
用户：双击 Setup.exe 安装后打开“启航电商 OMS”（关闭窗口即停服务）；zip 覆盖原目录同样用 QihangOMS.exe
```

## 纪律与踩坑教训

开发/发版的强制纪律及 2026-08 踩坑事故记录，已内化到 `.omp/rules/`（omp 会话自动加载）：

- `release-pipeline`（常驻）：打包只走 Build-Package / 门禁失败禁止发版 / 四条历史事故教训
- `sql-idempotency`（触发式）：编辑 `docs/sql/*.sql` 时强制幂等范式

PowerShell 脚本保持纯 ASCII（Windows PowerShell 5.1 无 BOM 中文会乱码）。
