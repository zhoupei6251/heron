-- Heron 业务菜单与权限种子。菜单 ID 固定在 1761400000000002000-1761400000000002999 保留段，
-- 与基座 1761400000000000001-1761400000000001623 不重叠；重复执行只会重写本段。
-- 幂等迁移由 E2 在授权环境执行，本文件不在现有 heron 库直接运行。

-- 先清理保留段：sys_role_menu 无外键，必须一起删，否则角色会残留指向已删菜单的授权。
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 1761400000000002000 AND 1761400000000002999;
DELETE FROM sys_menu WHERE menu_id BETWEEN 1761400000000002000 AND 1761400000000002999;

insert into sys_menu values(1761400000000002000, 'Heron 工作台', 0, 2, 'heron', null, '', 'N', 'Y', 'M', '0', '0', '', 'component', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'Heron 业务目录');

-- 任务中心
insert into sys_menu values(1761400000000002010, '任务中心', 1761400000000002000, 1, 'production', 'heron/production/index', '', 'N', 'Y', 'C', '0', '0', 'heron:production:list', 'list', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '统一生产任务中心');
insert into sys_menu values(1761400000000002011, '任务查询', 1761400000000002010, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:production:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002012, '任务取消', 1761400000000002010, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:production:cancel', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 新闻来源
insert into sys_menu values(1761400000000002020, '新闻来源', 1761400000000002000, 2, 'newsSource', 'heron/news/source/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsSource:list', 'cloud', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '受白名单约束的新闻来源采集');
insert into sys_menu values(1761400000000002021, '来源查询', 1761400000000002020, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002022, '来源新增', 1761400000000002020, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002023, '来源修改', 1761400000000002020, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002024, '来源删除', 1761400000000002020, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002025, '来源采集', 1761400000000002020, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:fetch', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 候选新闻
insert into sys_menu values(1761400000000002030, '候选新闻', 1761400000000002000, 3, 'newsCandidate', 'heron/news/candidate/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsCandidate:list', 'documentation', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '采集与人工录入的候选新闻');
insert into sys_menu values(1761400000000002031, '候选查询', 1761400000000002030, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsCandidate:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002032, '候选录入', 1761400000000002030, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsCandidate:manual', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002033, '候选领用', 1761400000000002030, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsCandidate:claim', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 新闻工作台
insert into sys_menu values(1761400000000002040, '新闻工作台', 1761400000000002000, 4, 'newsWork', 'heron/news/work/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsWork:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '新闻成片的生成、审片与下载');
insert into sys_menu values(1761400000000002041, '新闻查询', 1761400000000002040, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002042, '新闻编辑', 1761400000000002040, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002043, '视频生成', 1761400000000002040, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:generate', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002044, '人工审片', 1761400000000002040, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:review', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002045, '审片预览', 1761400000000002040, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:reviewPreview', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002046, '成片下载', 1761400000000002040, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:download', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002047, '跨用户访问', 1761400000000002040, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:all', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '允许访问他人拥有的新闻对象');

-- 原型项目
insert into sys_menu values(1761400000000002050, '原型项目', 1761400000000002000, 5, 'proto', 'heron/proto/index', '', 'N', 'Y', 'C', '0', '0', 'heron:proto:list', 'build', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '需求到 RP 原型的生成与交付');
insert into sys_menu values(1761400000000002051, '原型新增', 1761400000000002050, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002052, '原型查询', 1761400000000002050, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002053, '原型编辑', 1761400000000002050, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002054, '原型生成', 1761400000000002050, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:generate', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002055, '原型审核', 1761400000000002050, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:review', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002056, '原型验证', 1761400000000002050, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:verify', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002057, '原型交付', 1761400000000002050, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:deliver', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002058, '原型预览', 1761400000000002050, 8, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:reviewPreview', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002059, '原型下载', 1761400000000002050, 9, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:download', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002060, '跨用户访问', 1761400000000002050, 10, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:all', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '允许访问他人拥有的原型项目');
