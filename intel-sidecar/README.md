# Market Intel Sidecar

只读采集小红书公开笔记，进程固定监听 `127.0.0.1:18080`。

账号通过 OMS“市场情报 / 采集配置”页面扫码连接。正式 Cookie 使用 Windows DPAPI
加密后保存在 `%LOCALAPPDATA%\QihangOMS\market-intel\credentials.bin`，不进入项目目录、
安装包、Git、MySQL 或日志。加密数据只能由扫码时的 Windows 用户在同一台电脑解密。

## Setup (Windows)

1. 运行 `install-spider.bat`。脚本创建隔离的 `.venv`，并把 Spider_XHS 锁定到 `e1888d712519040f5fcc294baeac4b9505b25c98`。
2. 将 `.env.example` 复制为 `.env`，填写随机长 `TOKEN`。`COOKIES` 仅用于开发机 P0
   兼容验证，正常使用不要填写。
3. OMS 进程设置相同的 `MARKET_INTEL_TOKEN` 环境变量。
4. 用真实竞品主页做只读 P0 验证：

```bat
.venv\Scripts\python.exe p0_verify.py "https://www.xiaohongshu.com/user/profile/USER_ID?xsec_token=TOKEN&xsec_source=pc_search"
```

5. P0 全部通过后执行 `start.bat`，再从 OMS 配置页扫码。

数据库先执行 `docs/sql/market_intel.sql`。不要在 `.env`、日志或 SQL dump 中复制 Cookie。
