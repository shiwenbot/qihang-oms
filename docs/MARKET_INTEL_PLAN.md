# 市场情报系统 · 实现计划 v0.3

> 用途：学习 / 内部研究公开笔记趋势，不用于对外售卖数据、不接自动发布。
> 范围：首期只做小红书。抖音不做。
> 采集器：`cv-cat/Spider_XHS`（2026-08 仍在维护）。不用 MediaCrawler。
> 对照：取代 v0.2 里「MediaCrawler 共库」方案。OMS 看板 / 聚合 / 告警设计保留。

---

## 0. 你每天打开 OMS 会看到什么

女装选品每天就三件事。做成五个页（配置单独作为第五页）：

| 页 | 回答的问题 | 你看到的 |
|---|---|---|
| 今日热榜 | 现在什么火？ | 每个关键词一张「前 20 笔记」表：标题、作者、赞/藏/评、链接 |
| 关键词趋势 | 这个词这周热不热？ | 折线图：最近 14 天热度；旁边是词列表 |
| 竞对动态 | 同行发了啥？ | 你圈定的账号：粉丝、最近笔记、今天是否有新帖 |
| 告警中心 | 有没有突然爆的？ | 「新中式 +82%，昨天 1200 赞 → 今天 2180」 |

另有一个配置页：种子词、竞品主页 URL、采集账号登录状态。

系统自己干的：每天凌晨用学习小号登录小红书，按你的词去搜、按你圈的账号去翻主页，由 Java 接收结果并写进 MySQL，再算榜、算趋势、算告警。你早上来看表。

---

## 1. 为什么换采集器

v0.2 绑的是 MediaCrawler。它 2026-07 起把作者信息做成空操作：不落 `user_id` / 主页 / 粉丝，只留不可逆哈希。热榜和趋势还能做，**竞对监控做不了**。这是政策，不是 bug，fork 回去等于跟上游对着干。

`Spider_XHS` 是小红书专用 Python API 库（MIT，7k+ star，2026-07-25 升过签名）。接口和三个问题一一对应：

| 功能 | 调用 |
|---|---|
| 热榜 | `search_some_note(词, 20, sort=最多点赞)` |
| 相关词 | `get_search_keyword(词)` |
| 找账号 | `search_user(品牌名)` |
| 竞品资料 | `get_user_info(user_id)` → nickname / red_id / fans |
| 竞品动态 | `get_user_all_notes(主页URL)` |
| 笔记详情 | `get_note_info(笔记URL)` |
| 以后可选 | 蒲公英 `PuGongYingAPI` 按类目捞女装 KOL（首期不做） |

它不自带 MySQL、不自带看板。正好：表我们自己建，OMS 自己算，上游再改策略也砍不掉作者字段。

不选的：

- `ReaJason/xhs`：签名停更，经常 406
- `XHS-Downloader`：下载器，不是情报
- `xhs_one_spider`：源码不公开
- `XHS_ALL_IN_ONE`：独立后台，和 OMS 抢入口

---

## 2. 架构（人话）

三层，互不堵车。

```
┌──────────────────────────────────────────────────────────┐
│  OMS 网页（Vue2）                                          │
│  市场情报：热榜 / 趋势 / 竞对 / 告警 / 配置                    │
└────────────────────────────┬─────────────────────────────┘
                             │ REST
┌────────────────────────────▼─────────────────────────────┐
│  OMS Java（现有 api 进程）                                  │
│  · 定时任务 801：逐商户创建 job，HTTP POST 给 sidecar，立刻返回 │
│  · 定时任务 802：纯 SQL 算榜 / 趋势 / 告警                    │
│  · 采集结果落库、租户隔离、查询接口                           │
└───────────────┬──────────────────────────▲───────────────┘
                │ 127.0.0.1:18080          │ 采集结果（不接 OMS 数据库）
                ▼                          │
┌──────────────────────────────────────────┴───────────────┐
│  intel-sidecar（Python FastAPI，本机）                     │
│  包装 Spider_XHS：搜词、翻主页、拉笔记详情                     │
│  Cookie 只存在 sidecar；只绑 127.0.0.1                       │
│  限速：请求间隔 ≥ 3s，每轮最多 50 词 + N 个竞品                 │
└──────────────────────────────────────────────────────────┘
                             │
                             ▼
                  Java 接收结果后写入 MySQL 的 mi_* 表
```

关键不变量：

1. **OMS 不跑爬虫。** Java 定时任务只发指令、读结果。Spider_XHS 升级不用改 Java。
2. **采集任务必须扔出去就返回。** Spring 默认调度是单线程：`SchedulingConfiguration` 没 `setScheduler`/`setPoolSize`，`CronTask(task::poll)` 和 `CronTaskLoader` 的 10 分钟刷新跑在同一条车道上。现有唯一活任务 `PddOrderPullTask.poll()` 已经是同步拉所有 PDD 店。采集若堵在 `poll()` 里，订单拉取和 cron 刷新都会排队。
3. **sidecar 不直写 OMS 数据库。** sidecar 只负责采集和返回结果，Java 校验 job 归属后落库；Cookie 和平台响应不进入 OMS 数据库。
4. **sidecar 只监听本机。** 不对局域网开放。

---

## 3. 数据怎么流（一天一轮）

```
02:10  任务 801 醒来
      Java 查询启用情报的商户，逐商户创建 mi_job_run(pending)
      Java POST /jobs/run { job_id, keywords, competitors, request_token }
      收到 202 后把对应 job 标记 running，poll() 结束

02:10~  sidecar 慢慢干（不占 Java 调度线程）
        对每个词：search_some_note(热门, 20) → 返回标准化笔记结果
        对每个竞品：get_user_info + get_user_all_notes(最近 30 条)
                  → 返回竞品资料和笔记结果
        点赞/收藏/评论转 INT 再落库（原文是字符串，直接 ORDER BY 会乱）
        Java 校验 request_token 与 job/merchant 绑定后 upsert 结果；
        全部完成后仅允许 Java 将仍为 running 的 job 标记 success，单笔失败记录 error_count 后继续

06:00  任务 802 醒来
      只处理已 success 的采集轮次，按 merchant_id + 词聚合“采集窗口结束日期”的 raw（默认当天 02:10 轮次）
      → mi_rank_daily（每词 Top 20）
      → mi_keyword_daily（热度 = Σ log1p(赞+藏*2+评)）
      → 若今日热度 / 昨日热度 ≥ 1.8，写 mi_alert
      poll() 只跑 SQL，秒级结束

早上   打开五个页，读聚合表，不读 raw
```

手动「立刻跑一次」走同一个 sidecar 接口，仍然异步。

---

## 4. 表（OMS 自建，前缀 `mi_`）

脚本落点：`docs/sql/market_intel.sql`（和 `docs/sql/ai_image.sql` 同一套路）。

所有业务表带 `merchant_id`。查询接口强制带当前用户的商户，禁止跨商户。验收：A 商户加的竞品品牌词，B 商户热榜 / 配置 / 告警都看不到。

### 4.1 配置

`mi_keyword`：商户的种子词。`keyword` + `enabled` + `sort_type`（默认最多点赞）。

`mi_competitor`：商户圈定的账号。

| 字段 | 来源 |
|---|---|
| `user_id` | 主页 URL 路径最后一段 |
| `xsec_token` | URL 查询参数，拉主页笔记要用 |
| `profile_url` | 用户粘贴的完整链接 |
| `nickname / red_id / fans / follows` | `get_user_info` |
| `last_note_id / last_crawled_at` | 每次采集后更新 |

竞品不能「自动发现」为首期闭环：运营从 XHS 复制主页链接贴进来。URL 形如：

```
https://www.xiaohongshu.com/user/profile/{user_id}?xsec_token=...&xsec_source=pc_search
```

没有 `xsec_token` 的链接，sidecar 会拒收并提示「请从登录后的搜索结果或推荐页复制」。

`mi_collector`：采集小号（系统级，不分商户）。只存 Cookie 是否有效、最后登录时间、脱敏后的 nickname。Cookie 本体放 sidecar 的 `.env`，不进 MySQL、不进 git。

### 4.2 原始与聚合

`mi_note_raw`：一条采集快照一行，按 `(merchant_id, note_id, source, crawl_run_id)` 幂等 upsert；同一笔记命中多个关键词时通过 `mi_note_keyword` 关联表保存全部关键词。

- `source` = `search` / `user`
- `liked_count / collected_count / comment_count` 必须是 INT
- `keyword` 可空（竞品主页来的没有词）；搜索结果的关键词关系写入 `mi_note_keyword`
- `user_id / nickname` 必填（这就是换采集器的原因）

`mi_rank_daily`：`(merchant_id, keyword, stat_date, rank)` → note_id + 三项互动。

`mi_keyword_daily`：`(merchant_id, keyword, stat_date)` → note_count、like_sum、heat_score。

`mi_alert`：暴涨记录。`pct_change`、`heat_today`、`heat_yesterday`、`status`（未读/已读）。阈值默认 80%，写在配置常量里，不做 UI。

`mi_job_run`：每次采集/聚合一条日志。给「告警页」和排障看。

`mi_note_keyword`：`(merchant_id, crawl_run_id, note_id, keyword)` 唯一，保证同一笔记可属于多个词。

所有写入使用事务或幂等 upsert。重跑同一 `crawl_run_id` 不增加重复快照；同一商户已有 `pending/running` 任务时，手动触发返回已有 job，不并发启动第二轮。超过 30 分钟未更新的 running job 由 802 标记 timeout，允许下一轮重新创建。

802 只聚合 `success` 的 job；`fail/timeout` 不进入榜单。昨日无数据或昨日热度为 0 时不计算涨幅、不生成暴涨告警，避免除零和首日误报。

---

## 5. 接到 OMS 的硬约束（已核对仓库）

### 5.1 定时任务编号：801 / 802

`sys_task` 现有 ID（`docs/qihang-oms.sql`）：

```
11, 21, 22, 23, 25, 26, 51-53,
101-103, 201-202, 281-282, 301-304, 401-402, 501-502, 601, 701
```

21/22 是内部/三方推送，701 是「拉取小红书订单」。按平台分段，800 段空着。

| id | 名字 | cron 建议 | poll() 做什么 |
|---|---|---|---|
| 801 | 市场情报采集 | `10 2 * * *` | POST sidecar，写 job_run，return |
| 802 | 市场情报聚合 | `0 6 * * *` | 只跑 SQL |

样板：`api/.../task/PddOrderPullTask.java`。新任务同样 `implements IPollableService`，`getCronExpression()` 读 `taskService.getById(801)` / `getById(802)`，**必须和种子 ID 一致**。反面教材：`PddOrderPullTask` 写的是 `getById(3)`，种子里 PDD 拉单是 `301`，id=3 根本不存在，任务等于永久禁用。801/802 的 Java 和 `docs/sql/market_intel.sql` 一起加，禁止再写错号。

`poll()` 里禁止 `Thread.sleep`、禁止等 sidecar 跑完。`CronTaskLoader` 每 10 分钟刷新 cron。这是 801 必须异步的原因。

### 5.2 菜单：8100 段

`ai_image.sql` 已经占用 8000/8001。市场情报用 8100：

| menu_id | 名称 | 路由 | 组件 |
|---|---|---|---|
| 8100 | 市场情报 | `intel` | Layout 目录 |
| 8101 | 今日热榜 | `rank` | `intel/rank` |
| 8102 | 关键词趋势 | `trend` | `intel/trend` |
| 8103 | 竞对动态 | `competitor` | `intel/competitor` |
| 8104 | 告警中心 | `alert` | `intel/alert` |
| 8105 | 采集配置 | `config` | `intel/config` |

前端图表：现成 `echarts@5.4.0` + `views/dashboard/LineChart.vue`（macarons 主题）。趋势页复用折线，热度字段换成 `heat_score`。不要引新图表库。

店铺类型：`EnumShopType.XHS = 700`。情报模块不绑店铺，只是别和订单的 700 搞混。


### 5.3 多租户
没有全局 MyBatis 租户拦截器（`MybatisPlusConfig` 只有分页插件，架构文档里那句「拦截器自动注入 tenantId」是空的）。订单/商品靠 service 手写 `merchant_id`。登录时 `UserDetailsServiceImpl` 把 `user.deptId` 映成 `UserVo.merchantId`。情报照做，而且更严：

- **写和读都从登录用户的 `UserVo.merchantId` 取，不信前端 query 里的 `merchantId`。** 现有不少页面是前端把 merchantId 当请求参数传的，换个数字就能看隔壁商户。情报接口禁止这条路径。总部管理员按源码的 `userType="00"` / `EnumUserType.SUPPER` 判断，才允许显式切商户；普通商户不能切换。
- 所有 list/detail SQL 带 `eq(merchant_id, 当前商户)`
- 配置页的关键词、竞品按商户隔离
- 801 从 `UserVo.merchantId`/商户配置逐商户发起任务；sidecar 不接受任意 `merchant_id` 作为授权依据

验收：用两个测试商户各配一个别人看不到的品牌词，交叉查询必须为空；用浏览器改 query 里的 merchantId 也必须仍只看到自己的。

### 5.4 sidecar 进程

目录：`qihang-oms/intel-sidecar/`（OMS 仓库内，不另开产品）。

- Python 3.10+，Node 20+（Spider_XHS 签名要跑 JS）
- 依赖：FastAPI + 把 Spider_XHS 当库 import（git submodule 或 vendor 一份，锁 commit）
- 启动：只绑 `127.0.0.1:18080`
- 鉴权：共享本机 token 只用于传输层；每个 job 另带 Java 生成的随机 `request_token`，sidecar 回调/结果必须带回，Java 校验 job、merchant、token 三者绑定后才落库。没有 token 的请求直接 401
- Cookie：sidecar `.env` 的 `COOKIES=`，失效时 `/health` 返回 `cookie_ok=false`，配置页红字「需要重新登录」
- 登录：首期用浏览器复制 Cookie。二维码登录（Spider_XHS 有）放到 P1 之后，不阻塞五个页

Java 调 sidecar：新增带 `requestFactory` 的 `RestTemplate` Bean，connect/read 超时均为 3 秒（现有 `new RestTemplate()` 没有该配置，不能直接复用）。只关心 HTTP 202；连接失败、超时、非 202 都把 job 标为 fail。采集结果通过 sidecar 的结果回传接口提交，Java 仍不等待采集完成。

Windows 本机：sidecar 用 `hub`/启动器拉起，或一个 `intel-sidecar/start.bat`。打安装包那步以后再说，本计划不改 Inno Setup。

---

## 6. 五个页做什么（实现口径）

### 今日热榜 `intel/rank`

- 筛选：词、日期（默认今天）
- 表：rank、封面、标题、作者、赞/藏/评、笔记链接
- 数据：`mi_rank_daily` join `mi_note_raw`
- 空数据：显示「今日尚未采集」，按钮「手动跑一次」（调 801 同源接口）

### 关键词趋势 `intel/trend`

- 左：词列表（来自 `mi_keyword`）
- 右：`LineChart`，14 天 `heat_score`
- 数据：`mi_keyword_daily`

### 竞对动态 `intel/competitor`

- 上：已圈账号卡片（头像、昵称、粉丝、上次采集）
- 下：该账号最近笔记（来自 `mi_note_raw` where source=user）
- 「添加竞品」：粘贴主页 URL，Java 校验 `user/profile/{id}` + `xsec_token`，再让 sidecar 拉一次资料预览，确认后入库
- 没有「系统自动推荐同行」（需要蒲公英，二期）

### 告警中心 `intel/alert`

- 列表：词、涨幅、今/昨热度、时间
- 点进去跳热榜该词
- 标记已读

### 采集配置 `intel/config`

- 词 CRUD，默认 50 个上限（sidecar 硬限制，多了拒收）
- 竞品 CRUD
- 采集账号状态（只读）
- 「立即采集」按钮

种子词首期手工填，建议女装向：连衣裙、新中式、通勤、法式、吊带、阔腿裤、小香风、新中式连衣裙、醋酸、真丝。不做自动扩词。

页面数量为五个：rank、trend、competitor、alert、config。

---

## 7. 后端接口（OMS）

前缀 `/api/intel/`。权限字 `intel:rank:list` 等，和菜单 `perms` 对齐。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/rank` | 热榜。query: keyword, date |
| GET | `/trend` | 趋势。query: keyword, days=14 |
| GET | `/competitor` | 竞品列表 |
| GET | `/competitor/{id}/notes` | 该竞品笔记 |
| POST | `/competitor` | 粘贴 URL 添加 |
| DELETE | `/competitor/{id}` | 删除 |
| GET | `/alert` | 告警列表 |
| PUT | `/alert/{id}/read` | 已读 |
| GET/POST/PUT/DELETE | `/keyword` | 词 CRUD |
| GET | `/status` | sidecar 健康 + 最近 job_run |
| POST | `/run` | 当前商户手动触发采集（仍异步；已有 pending/running 时返回已有 job） |
| POST | `/internal/intel/jobs/{id}/result` | sidecar 回传标准化结果；仅本机 + request_token 可调用 |

所有 GET 在 service 层加 `merchant_id`。`/run` 从当前用户上下文取商户，生成 job 和一次性 `request_token`，把当前商户的词和竞品打包给 sidecar；前端传入的 `merchantId` 忽略。

---

## 8. sidecar 接口（本机）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | cookie_ok、last_ok_at |
| POST | `/jobs/run` | body: `job_id, keywords[], competitors[], request_token`。202 + job_id；不接受调用方自选 merchant_id |
| GET | `/jobs/{id}` | pending/running/success/fail/timeout |

`/jobs/run` 内部串行：词 → 竞品，每请求 `sleep(3~6s)`。单笔记失败记日志继续，不整轮失败。赞数字段 `int("1.2万" → 12000)` 在结果标准化阶段转换；Java 负责校验并落库，不接受 sidecar 直接写 OMS 表。结果提交必须可重试且按 `crawl_run_id` 幂等。

Spider_XHS 调用约定（学习用途，只用读接口）：

```python
from apis.xhs_pc_apis import XHS_Apis
api = XHS_Apis()
ok, msg, notes = api.search_some_note(query, 20, cookies, sort_type_choice=2)  # 2=最多点赞
ok, msg, user  = api.get_user_info(user_id, cookies)
ok, msg, posts = api.get_user_all_notes(profile_url, cookies)
```

不接创作者平台发布、不接蒲公英邀请、不下载无水印大文件（封面 URL 存链接即可）。

---

## 9. 风控与学习边界（部署必做，不是建议）

1. sidecar bind `127.0.0.1`，防火墙不放行 18080。
2. 每天一轮，最多 50 词，请求间隔 ≥ 3s。
3. 只用自己的学习小号 Cookie；Cookie 泄漏 = 号没了。
4. 不把 Cookie 写进 git、SQL dump、日志。
5. 不做评论区骚扰、不做批量关注、不做自动发笔记。
6. 平台改签名导致 406：停采集、看 Spider_XHS 是否已更新，不自己逆算法。
7. 本计划是学习研究公开内容的结构。上线前确认用途仍是内部学习，不是对外数据产品。

---

## 10. 分期（仍约一周半，一人）

| 阶段 | 干什么 | 人日 | 完成标准 |
|---|---|---|---|
| **P0 验证** | 本机跑通 Spider_XHS 三件事，不写 OMS | 0.5 | 见下方清单 |
| **P1 采集** | sidecar + `mi_*` 表 + 任务 801 异步触发 | 1.5 | 手动点一次，库里有带作者的笔记 |
| **P2 聚合** | 任务 802 + 竞品配置 API | 1.5 | 连续两天有 `mi_keyword_daily`；人为把昨热度改小能打出告警 |
| **P3 看板** | 五个 Vue 页 + 菜单 8100 段 + 权限 | 2.5 | 两个测试商户交叉看不到对方的词 |
| 合计 | | ≈6 | |

每周 Cookie 过期时，浏览器登录复制一次，几分钟。

### P0 清单（过了才允许写 Java）

在 `intel-sidecar/` 用真实小号 Cookie 跑脚本，三项全绿：

1. `search_some_note("新中式连衣裙", 20, sort=最多点赞)` 返回 ≥ 10 条，每条能读到 `note_id`、标题、点赞、作者 `user_id`+`nickname`
2. 粘一张竞品主页 URL，`get_user_info` 有 fans，`get_user_all_notes` 有最近笔记
3. 点赞字段能转成 INT（含「万」）

P1/P2 还必须验证：两个商户各自触发任务时，结果只能落到对应 `merchant_id`；重复点击「立即采集」不会产生并发 job；sidecar 重试结果不会产生重复 raw；02:10 采集轮次在 06:00 聚合为同一 `stat_date`，跨日时区固定为 `Asia/Shanghai`。

任何一项红：停，不要开工 P1。常见红因：Cookie 未登录、缺 `xsec_token`、签名 406（等 Spider_XHS 更新或换号）。

P0 **不**测发布接口、不测蒲公英。

---

## 11. 明确不做（首期）

- 抖音 / 其它平台
- 评论情绪分析、图片下载本地
- 自动发现竞品、蒲公英 KOL 筛选
- 自动发笔记、AI 改写
- 改 Inno 安装包、改启动器
- 把 sidecar 暴露到公网
- 共享 raw 跨商户（即使词相同也各爬各的，隔离优先）

---

## 12. 目录落点

```
qihang-oms/
  docs/MARKET_INTEL_PLAN.md          ← 本文件
  docs/sql/market_intel.sql          ← 表 + 菜单 8100 + sys_task 801/802
  intel-sidecar/                     ← FastAPI + Spider_XHS
    .env                             ← COOKIES / TOKEN（gitignore）
    app.py
    start.bat
  api/src/main/java/.../task/
    MarketIntelPullTask.java         ← 801
    MarketIntelAggTask.java          ← 802
  api/src/main/java/.../controller/intel/
  vue2/src/views/intel/{rank,trend,competitor,alert,config}.vue
  vue2/src/api/intel.js
```

---

## 13. 和 v0.2 的差异（给对照）

| v0.2 | v0.3 |
|---|---|
| MediaCrawler 共库 | Spider_XHS sidecar，OMS 自建 `mi_*` |
| 任务号 21/22 | 801/802（21/22 已被推送占用） |
| 菜单未避开 8000 | 8100 段（8000 是 AI 生图） |
| 竞对靠 `xhs_creator` 表 | 运营贴主页 URL；作者字段来自 `get_user_info` |
| 「锁定 MediaCrawler release」 | 无 release 可锁；改为锁 Spider_XHS commit |
| 爬虫控制台无密码 | sidecar 本机 + token，不启 MediaCrawler WebUI |
| 默认 csv/15 条 | 我们自己写库、明确 20 条、数字转 INT |
| 含抖音 | 砍掉，只留 XHS |

OMS 单车道调度、多租户两层过滤、五个看板，这三块 v0.2 是对的，按本版租户和回写规则执行。

---

核对：OMS `master` 当前树 · `sys_task` / `sys_menu` 来自 `docs/qihang-oms.sql` · 调度来自 `SchedulingConfiguration` + `PddOrderPullTask` · 采集接口来自 `cv-cat/Spider_XHS` `apis/xhs_pc_apis.py`（2026-08）。
# Security and packaging implementation note

The shipped application never contains a login Cookie. Each operator connects an account by QR code after installation; Windows DPAPI stores the credential under `%LOCALAPPDATA%\QihangOMS\market-intel\<provider>\<account_id>.bin`. Provider-specific profile access tokens are AES-GCM encrypted in MySQL and public URLs are stored without sensitive query parameters.

The Windows package is built in a new staging directory. MySQL is initialized on first launch, and OMS, MySQL, Redis, and the sidecar bind to `127.0.0.1`. Start/stop scripts track installation-owned PIDs and verify executable paths before stopping them.
