-- 内容决策中心菜单。用 1761400000000004000 段：004-menu.sql 只清理它的 2000-2999。
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 1761400000000004000 AND 1761400000000004099;
DELETE FROM sys_menu WHERE menu_id BETWEEN 1761400000000004000 AND 1761400000000004099;

insert into sys_menu values(1761400000000004000, '内容决策', 1761400000000002000, 13, 'decision', 'heron/decision/index', '', 'N', 'Y', 'C', '0', '0', 'heron:decision:list', 'checked', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'Agent 给出的内容判断，以及人的采纳与否决');
insert into sys_menu values(1761400000000004001, '决策查询', 1761400000000004000, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:decision:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000004002, '决策裁决', 1761400000000004000, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:decision:decide', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '采纳或否决 Agent 的判断');
