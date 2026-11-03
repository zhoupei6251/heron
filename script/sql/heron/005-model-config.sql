-- Heron 模型配置三键（OpenAiModelClient 读取）
-- config_value 一律留空：留空等同未配置，读序是 sys_config → Environment（yml 或 HERON_MODEL_* 变量）。
-- 真实 base-url / model / api-key 由部署侧在「参数管理」里填，api-key 不写进本文件，也不进 Git。
insert into sys_config values(1761700000000000101, 'Heron-模型服务地址', 'heron.model.base-url', '', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null,
                              'OpenAI 兼容端点，不含 /chat/completions；留空则回落环境变量 HERON_MODEL_BASE_URL');
insert into sys_config values(1761700000000000102, 'Heron-模型名称', 'heron.model.name', '', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null,
                              '两线共用单一模型；留空则回落环境变量 HERON_MODEL_NAME');
insert into sys_config values(1761700000000000103, 'Heron-模型密钥', 'heron.model.api-key', '', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null,
                              '页面只回显已配置；留空则回落环境变量 HERON_MODEL_API_KEY');
