package org.dromara.heron.service.impl.shared.support;

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

    /**
     * 取当前登录用户 ID。
     *
     * @return 当前登录用户 ID
     * @throws HeronAccessException 没有登录上下文
     */
    public Long requireActor() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new HeronAccessException();
        }
        return userId;
    }

    /**
     * 对象级权限：超管直接放行；否则要求当前用户是对象所有者或拥有该产线全量权限，
     * 并且拥有动作权限 {@code action}。
     *
     * @param ownerId 对象所有者用户 ID
     * @param type    业务产线，用于取全量权限串
     * @param action  具体动作权限串
     * @throws HeronAccessException 未登录或任一条件不满足
     */
    public void requireObject(long ownerId, ProductionBizType type, String action) {
        Long actor = requireActor();
        if (LoginHelper.isSuperAdmin(actor)) {
            return;
        }
        boolean scope = actor.equals(ownerId) || StpUtil.hasPermission(type.allPermission());
        if (!scope || !StpUtil.hasPermission(action)) {
            throw new HeronAccessException();
        }
    }

    /**
     * true 表示当前用户是超管或就是任务发起人。
     *
     * @throws HeronAccessException 未登录
     */
    public boolean isSelfOrAdmin(long taskActorId) {
        Long actor = requireActor();
        return LoginHelper.isSuperAdmin(actor) || actor == taskActorId;
    }

    /**
     * 要求当前用户拥有指定权限；超管放行。
     *
     * @throws HeronAccessException 未登录或无该权限
     */
    public void requirePermission(String permission) {
        Long actor = requireActor();
        if (!LoginHelper.isSuperAdmin(actor) && !StpUtil.hasPermission(permission)) {
            throw new HeronAccessException();
        }
    }

    /**
     * 要求当前用户至少拥有其中一个权限；超管放行。
     *
     * @throws HeronAccessException 未登录或一个权限都没有
     */
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

    /**
     * true 表示当前用户拥有该产线的全量权限（超管恒为 true）。
     *
     * @throws HeronAccessException 未登录
     */
    public boolean hasAllScope(ProductionBizType type) {
        return hasPermission(requireActor(), type.allPermission());
    }

    /**
     * 通知等后台路径使用：拿不到登录上下文或权限时返回 false，不抛出。
     */
    public boolean canNotifyActor(long actorId, long ownerId, ProductionBizType type) {
        if (!hasPermission(actorId, "heron:production:query")) {
            return false;
        }
        return actorId == ownerId || hasPermission(actorId, type.allPermission());
    }

    /** 委托产线枚举生成该产线的全量权限串（用于文档或种子数据，不做判定）。 */
    public static String generatePermission(ProductionBizType type) {
        return type.generatePermission();
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
