/**
 * 设置项的键与默认档：唯一一份，api 的设置页与 pg 仓储共用。
 *
 * 单独成一个不 import 驱动的文件：键名是契约不是实现，读它的地方不该为此把驱动拖进模块图。
 */

export const RETENTION_SETTING_KEY = "video_retention_days";
export const DEFAULT_RETENTION_DAYS = 30;

/**
 * 写稿模型的三项：页面上按租户配，环境变量兜底。
 * 地址默认七牛云；模型名不设默认——各家 id 不同，猜一个只会换来 404。
 */
export const MODEL_BASE_URL_SETTING_KEY = "model_base_url";
export const MODEL_API_KEY_SETTING_KEY = "model_api_key";
export const MODEL_NAME_SETTING_KEY = "model_name";
export const DEFAULT_MODEL_BASE_URL = "https://api.qnaigc.com/v1";
