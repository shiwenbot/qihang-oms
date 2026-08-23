-- Existing installations only. Fresh installations use market_intel.sql.
-- MySQL 8 migration is idempotent. Legacy plaintext profile tokens are removed;
-- affected competitors must be added again so the application can encrypt them.

DELIMITER //
DROP PROCEDURE IF EXISTS `mi_add_column`//
CREATE PROCEDURE `mi_add_column`(IN table_name_value VARCHAR(64), IN column_name_value VARCHAR(64), IN definition_value VARCHAR(500))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=table_name_value)
     AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=table_name_value AND column_name=column_name_value) THEN
    SET @statement_value=CONCAT('ALTER TABLE `',table_name_value,'` ADD COLUMN `',column_name_value,'` ',definition_value);
    PREPARE migration_statement FROM @statement_value;
    EXECUTE migration_statement;
    DEALLOCATE PREPARE migration_statement;
  END IF;
END//
DROP PROCEDURE IF EXISTS `mi_drop_column`//
CREATE PROCEDURE `mi_drop_column`(IN table_name_value VARCHAR(64), IN column_name_value VARCHAR(64))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=table_name_value AND column_name=column_name_value) THEN
    SET @statement_value=CONCAT('ALTER TABLE `',table_name_value,'` DROP COLUMN `',column_name_value,'`');
    PREPARE migration_statement FROM @statement_value;
    EXECUTE migration_statement;
    DEALLOCATE PREPARE migration_statement;
  END IF;
END//
DROP PROCEDURE IF EXISTS `mi_drop_index`//
CREATE PROCEDURE `mi_drop_index`(IN table_name_value VARCHAR(64), IN index_name_value VARCHAR(64))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=table_name_value AND index_name=index_name_value) THEN
    SET @statement_value=CONCAT('ALTER TABLE `',table_name_value,'` DROP INDEX `',index_name_value,'`');
    PREPARE migration_statement FROM @statement_value;
    EXECUTE migration_statement;
    DEALLOCATE PREPARE migration_statement;
  END IF;
END//
DROP PROCEDURE IF EXISTS `mi_add_index`//
CREATE PROCEDURE `mi_add_index`(IN table_name_value VARCHAR(64), IN index_name_value VARCHAR(64), IN definition_value VARCHAR(1000))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=table_name_value)
     AND NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=table_name_value AND index_name=index_name_value) THEN
    SET @statement_value=CONCAT('ALTER TABLE `',table_name_value,'` ADD ',definition_value);
    PREPARE migration_statement FROM @statement_value;
    EXECUTE migration_statement;
    DEALLOCATE PREPARE migration_statement;
  END IF;
END//
DELIMITER ;

CALL mi_add_column('mi_keyword','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_keyword','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_competitor','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_competitor','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_collector','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `id`");
CALL mi_add_column('mi_collector','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_job_run','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_job_run','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_note_raw','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_note_raw','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_note_keyword','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_note_keyword','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_rank_daily','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_rank_daily','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_keyword_daily','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_keyword_daily','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");
CALL mi_add_column('mi_alert','provider',"VARCHAR(32) NOT NULL DEFAULT 'xiaohongshu' AFTER `merchant_id`");
CALL mi_add_column('mi_alert','account_id',"VARCHAR(50) NOT NULL DEFAULT 'default' AFTER `provider`");

CALL mi_drop_index('mi_keyword','uk_mi_keyword_merchant_word');
CALL mi_drop_index('mi_keyword','idx_mi_keyword_enabled');
CALL mi_add_index('mi_keyword','uk_mi_keyword_merchant_word','UNIQUE INDEX `uk_mi_keyword_merchant_word` (`merchant_id`,`provider`,`account_id`,`keyword`)');
CALL mi_add_index('mi_keyword','idx_mi_keyword_enabled','INDEX `idx_mi_keyword_enabled` (`merchant_id`,`provider`,`account_id`,`enabled`)');

CALL mi_drop_index('mi_competitor','uk_mi_competitor_merchant_user');
CALL mi_drop_index('mi_competitor','idx_mi_competitor_enabled');
CALL mi_add_index('mi_competitor','uk_mi_competitor_merchant_user','UNIQUE INDEX `uk_mi_competitor_merchant_user` (`merchant_id`,`provider`,`account_id`,`user_id`)');
CALL mi_add_index('mi_competitor','idx_mi_competitor_enabled','INDEX `idx_mi_competitor_enabled` (`merchant_id`,`provider`,`account_id`,`enabled`)');
ALTER TABLE `mi_competitor` MODIFY COLUMN `xsec_token` VARCHAR(1000) NOT NULL COMMENT 'AES-GCM encrypted provider access token (legacy column name)';

CALL mi_add_index('mi_collector','uk_mi_collector_account','UNIQUE INDEX `uk_mi_collector_account` (`provider`,`account_id`)');

CALL mi_drop_index('mi_job_run','uk_mi_job_active_merchant');
CALL mi_drop_index('mi_job_run','uk_mi_job_active_scope');
CALL mi_drop_index('mi_job_run','idx_mi_job_merchant_status');
CALL mi_drop_index('mi_job_run','idx_mi_job_stat_date');
CALL mi_drop_column('mi_job_run','active_merchant_id');
CALL mi_drop_column('mi_job_run','active_scope');
CALL mi_add_column('mi_job_run','active_scope',"VARCHAR(160) GENERATED ALWAYS AS (CASE WHEN `job_type`='crawl' AND `status` IN ('pending','running') THEN CONCAT(`merchant_id`,':',`provider`,':',`account_id`) ELSE NULL END) STORED AFTER `aggregated_at`");
CALL mi_add_index('mi_job_run','uk_mi_job_active_scope','UNIQUE INDEX `uk_mi_job_active_scope` (`active_scope`)');
CALL mi_add_index('mi_job_run','idx_mi_job_merchant_status','INDEX `idx_mi_job_merchant_status` (`merchant_id`,`provider`,`account_id`,`status`,`job_type`)');
CALL mi_add_index('mi_job_run','idx_mi_job_stat_date','INDEX `idx_mi_job_stat_date` (`merchant_id`,`provider`,`account_id`,`stat_date`,`status`)');

CALL mi_drop_index('mi_note_raw','uk_mi_note_snapshot');
CALL mi_drop_index('mi_note_raw','idx_mi_note_user');
CALL mi_drop_index('mi_note_raw','idx_mi_note_run');
CALL mi_add_index('mi_note_raw','uk_mi_note_snapshot','UNIQUE INDEX `uk_mi_note_snapshot` (`merchant_id`,`provider`,`account_id`,`note_id`,`source`,`crawl_run_id`)');
CALL mi_add_index('mi_note_raw','idx_mi_note_user','INDEX `idx_mi_note_user` (`merchant_id`,`provider`,`account_id`,`user_id`,`source`,`crawled_at`)');
CALL mi_add_index('mi_note_raw','idx_mi_note_run','INDEX `idx_mi_note_run` (`merchant_id`,`provider`,`account_id`,`crawl_run_id`)');

CALL mi_drop_index('mi_note_keyword','uk_mi_note_keyword');
CALL mi_drop_index('mi_note_keyword','idx_mi_note_keyword_word');
CALL mi_add_index('mi_note_keyword','uk_mi_note_keyword','UNIQUE INDEX `uk_mi_note_keyword` (`merchant_id`,`provider`,`account_id`,`crawl_run_id`,`note_id`,`keyword`)');
CALL mi_add_index('mi_note_keyword','idx_mi_note_keyword_word','INDEX `idx_mi_note_keyword_word` (`merchant_id`,`provider`,`account_id`,`keyword`,`crawl_run_id`)');

CALL mi_drop_index('mi_rank_daily','uk_mi_rank_daily');
CALL mi_drop_index('mi_rank_daily','idx_mi_rank_note');
CALL mi_add_index('mi_rank_daily','uk_mi_rank_daily','UNIQUE INDEX `uk_mi_rank_daily` (`merchant_id`,`provider`,`account_id`,`keyword`,`stat_date`,`rank_no`)');
CALL mi_add_index('mi_rank_daily','idx_mi_rank_note','INDEX `idx_mi_rank_note` (`merchant_id`,`provider`,`account_id`,`crawl_run_id`,`note_id`)');

CALL mi_drop_index('mi_keyword_daily','uk_mi_keyword_daily');
CALL mi_add_index('mi_keyword_daily','uk_mi_keyword_daily','UNIQUE INDEX `uk_mi_keyword_daily` (`merchant_id`,`provider`,`account_id`,`keyword`,`stat_date`)');

CALL mi_drop_index('mi_alert','uk_mi_alert_daily');
CALL mi_drop_index('mi_alert','idx_mi_alert_status');
CALL mi_add_index('mi_alert','uk_mi_alert_daily','UNIQUE INDEX `uk_mi_alert_daily` (`merchant_id`,`provider`,`account_id`,`keyword`,`stat_date`)');
CALL mi_add_index('mi_alert','idx_mi_alert_status','INDEX `idx_mi_alert_status` (`merchant_id`,`provider`,`account_id`,`status`,`stat_date`)');

UPDATE `mi_competitor`
SET `profile_url`=SUBSTRING_INDEX(`profile_url`,'?',1), `xsec_token`='', `enabled`=0
WHERE `xsec_token` IS NOT NULL AND `xsec_token`<>'' AND `xsec_token` NOT LIKE 'v2:%';

DROP PROCEDURE IF EXISTS `mi_add_column`;
DROP PROCEDURE IF EXISTS `mi_drop_column`;
DROP PROCEDURE IF EXISTS `mi_drop_index`;
DROP PROCEDURE IF EXISTS `mi_add_index`;
