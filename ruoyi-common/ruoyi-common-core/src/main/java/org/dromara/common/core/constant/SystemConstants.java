package org.dromara.common.core.constant;

/**
 * 系统常量信息
 *
 * @author Lion Li
 */
public interface SystemConstants {

    /**
     * 正常状态
     */
    String NORMAL = "0";

    /**
     * 异常状态
     */
    String DISABLE = "1";

    /**
     * 是
     */
    String YES = "Y";

    /**
     * 否
     */
    String NO = "N";

    /**
     * 菜单类型（目录）
     */
    String TYPE_DIR = "M";

    /**
     * 菜单类型（菜单）
     */
    String TYPE_MENU = "C";

    /**
     * 菜单类型（按钮）
     */
    String TYPE_BUTTON = "F";

    /**
     * Layout组件标识
     */
    String LAYOUT = "Layout";

    /**
     * ParentView组件标识
     */
    String PARENT_VIEW = "ParentView";

    /**
     * InnerLink组件标识
     */
    String INNER_LINK = "InnerLink";

    /**
     * 超级管理员用户ID
     */
    Long SUPER_ADMIN_USER_ID = 1761100000000000001L;

    /**
     * 超级管理员角色ID
     */
    Long SUPER_ADMIN_ROLE_ID = 1761300000000000001L;

    /**
     * 超级管理员角色 roleKey
     */
    String SUPER_ADMIN_ROLE_KEY = "superadmin";

    /**
     * 根部门祖级列表
     */
    String ROOT_DEPT_ANCESTORS = "0";

    /**
     * 默认部门 ID
     */
    Long DEFAULT_DEPT_ID = 1761000000000000100L;

    /**
     * 排除敏感属性字段
     *
     * <p>令牌类必须在名单里：SSE 推送用 EventSource 发请求，带不了请求头，
     * 前端只能把令牌放进 URL 查询参数。名单里原来只有口令类，
     * 于是每次 SSE 连接都会把一份可直接使用的会话令牌写进应用日志，
     * 操作日志还会把同一份存进 sys_oper_log。
     *
     * <p>字段名按原样匹配，大小写各写一份（框架里的匹配是大小写敏感的）。
     */
    String[] EXCLUDE_PROPERTIES = {"password", "oldPassword", "newPassword", "confirmPassword",
        "Authorization", "authorization", "satoken", "token", "accessToken", "access_token",
        "secret", "secretKey", "secret_key", "apiKey", "api_key"};


}
