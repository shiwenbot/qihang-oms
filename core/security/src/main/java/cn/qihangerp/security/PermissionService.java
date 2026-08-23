package cn.qihangerp.security;

import cn.qihangerp.security.common.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

@Service("ss")
public class PermissionService {
    private static final String ALL_PERMISSION = "*:*:*";

    public boolean hasPermi(String permission) {
        if (!StringUtils.hasText(permission)) return false;
        try {
            Set<String> permissions = SecurityUtils.getLoginUser().getPermissions();
            return permissions != null && (permissions.contains(ALL_PERMISSION) || permissions.contains(permission.trim()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
