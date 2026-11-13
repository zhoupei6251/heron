-- 研究任务菜单与权限种子。
-- 用 1761400000000003000-1761400000000003099 这一段：004-menu.sql 会整段清理它自己的
-- 2000-2999 范围，把新菜单放进那段会被下一次重跑顺手删掉。
-- 重复执行只重写本段，不影响其它菜单。
--
-- 页面 src/views/heron/research/index.vue 已就位，父菜单 visible='0' 正常显示。

DELETE FROM sys_role_menu WHERE menu_id BETWEEN 1761400000000003000 AND 1761400000000003099;
DELETE FROM sys_menu WHERE menu_id BETWEEN 1761400000000003000 AND 1761400000000003099;

insert into sys_menu values(1761400000000003000, '研究任务', 1761400000000002000, 12, 'research', 'heron/research/index', '', 'N', 'Y', 'C', '0', '0', 'heron:research:list', 'education', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'Agent 对一条数据机会做的判断：依据、结论与消耗');
insert into sys_menu values(1761400000000003001, '研究查询', 1761400000000003000, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:research:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000003002, '发起研究', 1761400000000003000, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:research:run', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '建任务并排上执行；会消耗模型额度');
insert into sys_menu values(1761400000000003003, '取消研究', 1761400000000003000, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:research:cancel', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
