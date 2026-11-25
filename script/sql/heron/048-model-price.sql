-- Heron 模型单价与单次 run 花费上限（ModelPriceConfig 读取）
-- 单价单位是「元 / 千 token」：与厂商计价口径一致，运维填的时候不用自己换算。
-- 值一律留空：留空等同未配置 → 成本金额为 0 且标记为不可信，花费闸不生效。
-- 真实单价 / 上限由部署侧在「参数管理」里填，不进 Git。
insert into sys_config values(1761700000000000104, 'Heron-输入单价(元/千token)', 'heron.model.price-in', '', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null,
                              '成本概览折算金额用；留空则金额显示为不可信，不假装花了 0 元');
insert into sys_config values(1761700000000000105, 'Heron-输出单价(元/千token)', 'heron.model.price-out', '', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null,
                              '输出 token 通常比输入贵一个档，分开填');
insert into sys_config values(1761700000000000106, 'Heron-单次run花费上限(元)', 'heron.run.max-cost', '', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null,
                              '同一 task+attempt 累计到线即终止 run；留空表示不设天花板');
