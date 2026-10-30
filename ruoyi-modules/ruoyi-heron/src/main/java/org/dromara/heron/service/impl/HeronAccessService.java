package org.dromara.heron.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.heron.domain.HeronAccessException;
import org.dromara.heron.domain.ProductionBizType;
import org.springframework.stereotype.Service;

/**
 * 对象范围与动作权限判定。
 */
@Service
public class HeronAccessService {

    public Long requireActor() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new HeronAccessException();
        }
        return userId;
    }

    public void requireObject(long ownerId, ProductionBizType type, String action) {
        Long actor = requireActor();
        if (LoginHelper.isSuperAdmin(actor)) {
            return;
        }
        boolean scope = actor.equals(ownerId) || StpUtil.hasPermission(allPermission(type));
        if (!scope || !StpUtil.hasPermission(action)) {
            throw new HeronAccessException();
        }
    }

    public boolean isSelfOrAdmin(long taskActorId) {
        Long actor = requireActor();
        return LoginHelper.isSuperAdmin(actor) || actor == taskActorId;
    }

    public void requirePermission(String permission) {
        Long actor = requireActor();
        if (!LoginHelper.isSuperAdmin(actor) && !StpUtil.hasPermission(permission)) {
            throw new HeronAccessException();
        }
    }

    public void requireAnyPermission(String... permissions) {
        Long actor = requireActor();
        if (LoginHelper.isSuperAdmin(actor)) {
            return;
        }
        for (String permission : permissions) {
            if (StpUtil.hasPermission(permission)) {
                return;
            }
        }
        throw new HeronAccessException();
    }

    public boolean hasAllScope(ProductionBizType type) {
        return hasPermission(requireActor(), allPermission(type));
    }

    /**
     * 通知等后台路径使用：拿不到登录上下文或权限时返回 false，不抛出。
     */
    public boolean canNotifyActor(long actorId, long ownerId, ProductionBizType type) {
        if (!hasPermission(actorId, "heron:production:query")) {
            return false;
        }
        return actorId == ownerId || hasPermission(actorId, allPermission(type));
    }

    public static String generatePermission(ProductionBizType type) {
        return type == ProductionBizType.news_video ? "heron:newsWork:generate" : "heron:proto:generate";
    }

    public static String allPermission(ProductionBizType type) {
        return type == ProductionBizType.news_video ? "heron:newsWork:all" : "heron:proto:all";
    }

    private static boolean hasPermission(long userId, String permission) {
        if (LoginHelper.isSuperAdmin(userId)) {
            return true;
        }
        try {
            return StpUtil.hasPermission(userId, permission);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
