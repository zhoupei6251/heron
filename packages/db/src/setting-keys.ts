/**
 * 设置项的键与默认档：唯一一份，api 的设置页与 pg 仓储共用。
 *
 * 单独成一个不 import 驱动的文件：键名是契约不是实现，读它的地方不该为此把驱动拖进模块图。
 */

export const RETENTION_SETTING_KEY = "video_retention_days";
export const DEFAULT_RETENTION_DAYS = 30;
