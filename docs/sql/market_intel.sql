-- ============================================================
-- 市场情报（小红书）增量脚本
-- 依赖: qihang-oms 主库，MySQL 8.0+
-- ============================================================

CREATE TABLE IF NOT EXISTS `mi_keyword` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `keyword` VARCHAR(100) NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `sort_type` TINYINT NOT NULL DEFAULT 2 COMMENT 'Spider_XHS sort_type_choice, 2=最多点赞',
  `create_by` VARCHAR(64) NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_keyword_merchant_word` (`merchant_id`, `provider`, `account_id`, `keyword`),
  KEY `idx_mi_keyword_enabled` (`merchant_id`, `provider`, `account_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场情报种子词';

CREATE TABLE IF NOT EXISTS `mi_competitor` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `user_id` VARCHAR(100) NOT NULL,
  `xsec_token` VARCHAR(1000) NOT NULL COMMENT 'AES-GCM encrypted provider access token (legacy column name)',
  `profile_url` VARCHAR(1000) NOT NULL,
  `nickname` VARCHAR(200) NULL,
  `red_id` VARCHAR(100) NULL,
  `avatar_url` VARCHAR(1000) NULL,
  `fans` INT NOT NULL DEFAULT 0,
  `follows` INT NOT NULL DEFAULT 0,
  `last_note_id` VARCHAR(100) NULL,
  `last_crawled_at` DATETIME NULL,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_competitor_merchant_user` (`merchant_id`, `provider`, `account_id`, `user_id`),
  KEY `idx_mi_competitor_enabled` (`merchant_id`, `provider`, `account_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场情报竞品账号';

CREATE TABLE IF NOT EXISTS `mi_collector` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `nickname_masked` VARCHAR(100) NULL,
  `cookie_ok` TINYINT NOT NULL DEFAULT 0,
  `last_login_at` DATETIME NULL,
  `last_ok_at` DATETIME NULL,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_collector_account` (`provider`, `account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统级采集账号状态（不存Cookie）';

CREATE TABLE IF NOT EXISTS `mi_job_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `job_type` VARCHAR(20) NOT NULL DEFAULT 'crawl',
  `status` VARCHAR(20) NOT NULL DEFAULT 'pending',
  `request_token` CHAR(64) NULL,
  `stat_date` DATE NOT NULL,
  `item_count` INT NOT NULL DEFAULT 0,
  `error_count` INT NOT NULL DEFAULT 0,
  `error_msg` VARCHAR(1000) NULL,
  `started_at` DATETIME NULL,
  `finished_at` DATETIME NULL,
  `aggregated_at` DATETIME NULL,
  `active_scope` VARCHAR(160) GENERATED ALWAYS AS
    (CASE WHEN `job_type`='crawl' AND `status` IN ('pending','running')
      THEN CONCAT(`merchant_id`,':',`provider`,':',`account_id`) ELSE NULL END) STORED,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_job_active_scope` (`active_scope`),
  KEY `idx_mi_job_merchant_status` (`merchant_id`, `provider`, `account_id`, `status`, `job_type`),
  KEY `idx_mi_job_status_time` (`status`, `update_time`),
  KEY `idx_mi_job_stat_date` (`merchant_id`, `provider`, `account_id`, `stat_date`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场情报任务运行记录';

CREATE TABLE IF NOT EXISTS `mi_note_raw` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `crawl_run_id` BIGINT NOT NULL,
  `note_id` VARCHAR(100) NOT NULL,
  `source` VARCHAR(20) NOT NULL,
  `keyword` VARCHAR(100) NULL,
  `title` VARCHAR(500) NULL,
  `note_url` VARCHAR(1000) NULL,
  `cover_url` VARCHAR(1000) NULL,
  `user_id` VARCHAR(100) NOT NULL,
  `nickname` VARCHAR(200) NOT NULL,
  `liked_count` INT NOT NULL DEFAULT 0,
  `collected_count` INT NOT NULL DEFAULT 0,
  `comment_count` INT NOT NULL DEFAULT 0,
  `published_at` DATETIME NULL,
  `crawled_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_note_snapshot` (`merchant_id`, `provider`, `account_id`, `note_id`, `source`, `crawl_run_id`),
  KEY `idx_mi_note_user` (`merchant_id`, `provider`, `account_id`, `user_id`, `source`, `crawled_at`),
  KEY `idx_mi_note_run` (`merchant_id`, `provider`, `account_id`, `crawl_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场情报笔记采集快照';

CREATE TABLE IF NOT EXISTS `mi_note_keyword` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `crawl_run_id` BIGINT NOT NULL,
  `note_id` VARCHAR(100) NOT NULL,
  `keyword` VARCHAR(100) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_note_keyword` (`merchant_id`, `provider`, `account_id`, `crawl_run_id`, `note_id`, `keyword`),
  KEY `idx_mi_note_keyword_word` (`merchant_id`, `provider`, `account_id`, `keyword`, `crawl_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记与命中关键词关系';

CREATE TABLE IF NOT EXISTS `mi_rank_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `keyword` VARCHAR(100) NOT NULL,
  `stat_date` DATE NOT NULL,
  `rank_no` INT NOT NULL,
  `note_id` VARCHAR(100) NOT NULL,
  `crawl_run_id` BIGINT NOT NULL,
  `liked_count` INT NOT NULL DEFAULT 0,
  `collected_count` INT NOT NULL DEFAULT 0,
  `comment_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_rank_daily` (`merchant_id`, `provider`, `account_id`, `keyword`, `stat_date`, `rank_no`),
  KEY `idx_mi_rank_note` (`merchant_id`, `provider`, `account_id`, `crawl_run_id`, `note_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词每日Top20';

CREATE TABLE IF NOT EXISTS `mi_keyword_daily` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `keyword` VARCHAR(100) NOT NULL,
  `stat_date` DATE NOT NULL,
  `crawl_run_id` BIGINT NOT NULL,
  `note_count` INT NOT NULL DEFAULT 0,
  `like_sum` BIGINT NOT NULL DEFAULT 0,
  `heat_score` DECIMAL(18,4) NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_keyword_daily` (`merchant_id`, `provider`, `account_id`, `keyword`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词每日热度';

CREATE TABLE IF NOT EXISTS `mi_alert` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT NOT NULL,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu',
  `account_id` VARCHAR(50) NOT NULL DEFAULT 'default',
  `keyword` VARCHAR(100) NOT NULL,
  `stat_date` DATE NOT NULL,
  `pct_change` DECIMAL(12,4) NOT NULL,
  `heat_today` DECIMAL(18,4) NOT NULL,
  `heat_yesterday` DECIMAL(18,4) NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'unread',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `read_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mi_alert_daily` (`merchant_id`, `provider`, `account_id`, `keyword`, `stat_date`),
  KEY `idx_mi_alert_status` (`merchant_id`, `provider`, `account_id`, `status`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场情报暴涨告警';

-- 动态路由菜单：目录 + 五个页面。按钮权限由各页面 perms 覆盖首期接口。
INSERT INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`query`,`is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`) VALUES
(8100,'市场情报',0,91,'intel','Layout','',1,0,'M','0','0','','chart','admin',NOW(),'小红书公开笔记趋势'),
(8101,'今日热榜',8100,1,'rank','intel/rank','',1,0,'C','0','0','intel:rank:list','list','admin',NOW(),''),
(8102,'关键词趋势',8100,2,'trend','intel/trend','',1,0,'C','0','0','intel:trend:list','chart','admin',NOW(),''),
(8103,'竞对动态',8100,3,'competitor','intel/competitor','',1,0,'C','0','0','intel:competitor:list','peoples','admin',NOW(),''),
(8104,'告警中心',8100,4,'alert','intel/alert','',1,0,'C','0','0','intel:alert:list','bell','admin',NOW(),''),
(8105,'采集配置',8100,5,'config','intel/config','',1,0,'C','0','0','intel:config:list','system','admin',NOW(),''),
(8110,'添加竞品',8103,1,'','','',1,0,'F','0','0','intel:competitor:add','#','admin',NOW(),''),
(8111,'删除竞品',8103,2,'','','',1,0,'F','0','0','intel:competitor:remove','#','admin',NOW(),''),
(8112,'处理告警',8104,1,'','','',1,0,'F','0','0','intel:alert:edit','#','admin',NOW(),''),
(8113,'添加关键词',8105,1,'','','',1,0,'F','0','0','intel:config:add','#','admin',NOW(),''),
(8114,'编辑关键词',8105,2,'','','',1,0,'F','0','0','intel:config:edit','#','admin',NOW(),''),
(8115,'删除关键词',8105,3,'','','',1,0,'F','0','0','intel:config:remove','#','admin',NOW(),''),
(8116,'立即采集',8105,4,'','','',1,0,'F','0','0','intel:config:run','#','admin',NOW(),'')
ON DUPLICATE KEY UPDATE `menu_name`=VALUES(`menu_name`),`path`=VALUES(`path`),`component`=VALUES(`component`),`perms`=VALUES(`perms`);

INSERT INTO `sys_role_menu` (`role_id`,`menu_id`)
SELECT 2, menu_id FROM `sys_menu` WHERE menu_id BETWEEN 8100 AND 8116
ON DUPLICATE KEY UPDATE `menu_id`=VALUES(`menu_id`);

INSERT INTO `sys_task` (`id`,`task_name`,`cron`,`method`,`remark`,`create_time`,`status`) VALUES
(801,'市场情报采集','0 10 2 * * *',NULL,'异步触发小红书公开笔记采集',NOW(),1),
(802,'市场情报聚合','0 0 6 * * *',NULL,'生成热榜、趋势和暴涨告警',NOW(),1)
ON DUPLICATE KEY UPDATE `task_name`=VALUES(`task_name`),`cron`=VALUES(`cron`),`remark`=VALUES(`remark`),`status`=VALUES(`status`);
