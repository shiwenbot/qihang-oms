INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '系统名称', 'sys.name', '启航电商OMS订单中台', 'Y', 'admin', NOW(), '系统名称'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.name');
INSERT INTO sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '账号自助-验证码开关', 'sys.account.captchaEnabled', 'false', 'Y', 'admin', NOW(), '是否开启验证码功能（true开启，false关闭）'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'sys.account.captchaEnabled');
UPDATE sys_config SET config_value = 'false' WHERE config_key = 'sys.account.captchaEnabled' AND config_value <> 'false';
INSERT INTO sys_user (user_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar, password, status, del_flag, login_ip, login_date, create_by, create_time, update_by, update_time, remark)
SELECT 1, NULL, 'admin', 'Administrator', '00', '', '', '0', '', '$2a$10$nLjMnUJ8BL3DjiqlPMa4y.57PLydX8zuyyBuCPGgdj27Zlv1lIaUu', '0', '0', '', NULL, 'system', NOW(), '', NULL, 'Local administrator'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE user_id = 1);
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time, remark)
SELECT 1, 'Administrator', 'admin', 1, '1', 1, 1, '0', '0', 'system', NOW(), 'Local administrator'
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_id = 1);
INSERT INTO sys_user_role (user_id, role_id)
SELECT 1, 1 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id = 1 AND role_id = 1);
