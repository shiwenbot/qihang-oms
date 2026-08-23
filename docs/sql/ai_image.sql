-- ============================================================
-- AI生图功能 增量脚本
-- 依赖: qihang-oms 主库
-- 说明: menu_id 使用 8000 段，如与现场冲突请自行调整
-- ============================================================

-- ----------------------------
-- 生图任务表
-- ----------------------------
DROP TABLE IF EXISTS `ai_image_task`;
CREATE TABLE `ai_image_task` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `prompt`       VARCHAR(2000) NOT NULL               COMMENT '提示词',
  `size`         VARCHAR(20)  NOT NULL DEFAULT '1024x1024' COMMENT '尺寸 宽x高',
  `model`        VARCHAR(40)  NOT NULL DEFAULT 'auto' COMMENT '模型档位: auto/standard/2k/4k',
  `ref_type`     VARCHAR(10)  NOT NULL DEFAULT 'TEXT' COMMENT '参考图类型: TEXT纯文生图/URL网络参考图/FILE本地上传参考图',
  `ref_count`    INT          NOT NULL DEFAULT 0      COMMENT '参考图数量',
  `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '状态: 0待处理 1生成中 2成功 3失败',
  `result_url`   VARCHAR(500) NULL                   COMMENT '结果图地址',
  `error_msg`    VARCHAR(1000) NULL                  COMMENT '失败原因',
  `cost_seconds` INT          NULL                   COMMENT '耗时(秒)',
  `create_by`    VARCHAR(64)  NULL                   COMMENT '创建人',
  `create_time`  DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_by` (`create_by`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI生图任务表';

-- ----------------------------
-- 菜单: 顶级菜单「AI生图」(menu_type=C 顶级单页, 侧边栏直接显示)
-- ----------------------------
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES (8000, 'AI生图', 0, 90, 'aiImage', 'ai/image', '', 1, 0, 'C', '0', '0', 'ai:image:list', 'aiImage', 'admin', NOW(), 'AI生图菜单');

-- 按钮权限
INSERT INTO `sys_menu` (`menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`, `remark`)
VALUES (8001, 'AI生图生成', 8000, 1, '', '', '', 1, 0, 'F', '0', '0', 'ai:image:generate', '#', 'admin', NOW(), '');

-- 授权: 超级管理员(role_id=1)默认可见全部菜单, 此处给普通角色(role_id=2)示例授权, 按需调整
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (2, 8000);
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES (2, 8001);
