-- 待发布池菜单与权限：挂在 Heron 一级目录（1761400000000002000）下。
-- 回滚：删除本迁移新增的 sys_menu 行即可。
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002130, '待发布池', 1761400000000002000, 13, 'publish', 'heron/publish/index', '',
  'N', 'Y', 'C', '0', '0', 'heron:publish:list', 'list', '', 1761000000000000103,
  sysdate(), null, null, '发布库存：AI 评估、择优发布与人工立即发布'
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002000);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002131, '库存查询', 1761400000000002130, 1, '', '', '', 'N', 'Y', 'F',
  '0', '0', 'heron:publish:list', '#', '', 1761000000000000103, sysdate(), null, null, ''
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002130);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002132, '库存发布', 1761400000000002130, 2, '', '', '', 'N', 'Y', 'F',
  '0', '0', 'heron:publish:publish', '#', '', 1761000000000000103, sysdate(), null, null, ''
WHERE EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002130);
