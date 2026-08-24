-- ============================================================
-- 一次性默认密码重置：Andy@2025 -> admin
-- 背景: 旧发版包首装种子密码为 Andy@2025，现统一为 admin。
-- 幂等: 仅当 admin 的密码仍是旧默认哈希时才重置；
--       用户已自行修改过密码（哈希不同）则完全不干预。
-- ============================================================
UPDATE `sys_user`
SET `password` = '$2a$10$ib0ZSsf7EhY1jU/FeiQpleNbLVRZmGTL9w8RJNk6IZRVjuGUle6Cm',
    `update_by` = 'system',
    `update_time` = NOW()
WHERE `user_id` = 1
  AND `password` = '$2a$10$nLjMnUJ8BL3DjiqlPMa4y.57PLydX8zuyyBuCPGgdj27Zlv1lIaUu';
