-- Heron 业务菜单与权限种子。菜单 ID 固定在 1761400000000002000-1761400000000002999 保留段，
-- 与基座 1761400000000000001-1761400000000001623 不重叠；重复执行只会重写本段。
-- 幂等迁移由 E2 在授权环境执行，本文件不在现有 heron 库直接运行。
--
-- 2026-11-11 控制台重构：按「用户要做的事」排，不按后端 Controller 排。
--   选题雷达 → 编辑策划 → 视频作品 → 审片中心 → 发布记录 →
--   新闻来源 → 数据集 → 视频模板 → 生产任务 → 原型工坊
-- 仍是平铺，不建二级目录：目录层级靠若依的 ParentView 展开，路径前缀会跟着变，
-- 而本轮没有浏览器验收条件。等有一轮真机验收后再分组（见 docs/plans/2026-11-11-heron-console-redesign.md）。
-- 详情页走 query string 的隐藏菜单，不用 :param —— 菜单驱动的路由对路径参数不友好。

-- 先清理保留段：sys_role_menu 无外键，必须一起删，否则角色会残留指向已删菜单的授权。
DELETE FROM sys_role_menu WHERE menu_id BETWEEN 1761400000000002000 AND 1761400000000002999;
DELETE FROM sys_menu WHERE menu_id BETWEEN 1761400000000002000 AND 1761400000000002999;

insert into sys_menu values(1761400000000002000, 'Heron 工作台', 0, 2, 'heron', null, '', 'N', 'Y', 'M', '0', '0', '', 'component', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, 'Heron 业务目录');

-- 1 选题雷达：列表负责发现，详情负责判断
insert into sys_menu values(1761400000000002010, '选题雷达', 1761400000000002000, 1, 'topic', 'heron/news/topic/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsCandidate:list', 'documentation', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '热榜与 RSS 候选池，判断值不值得做');
insert into sys_menu values(1761400000000002011, '选题查询', 1761400000000002010, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsCandidate:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002012, '选题录入', 1761400000000002010, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsCandidate:manual', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002013, '选题认领', 1761400000000002010, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsCandidate:claim', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002019, '选题详情', 1761400000000002000, 91, 'topic-detail', 'heron/news/topic/detail', '', 'N', 'N', 'C', '1', '0', 'heron:newsCandidate:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '选题详情页，从列表带 candidateId 进入');

-- 2 编辑策划：一次自动运行的可追溯视图，只读
insert into sys_menu values(1761400000000002020, '编辑策划', 1761400000000002000, 2, 'editorial', 'heron/editorial/index', '', 'N', 'Y', 'C', '0', '0', 'heron:editorial:list', 'date', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '每天为什么走这条线、盘子有什么、产出了什么');
insert into sys_menu values(1761400000000002021, '运行查询', 1761400000000002020, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:editorial:list', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002022, '运行详情', 1761400000000002020, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:editorial:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 3 视频作品：列表 + 工作室两页，不再用长抽屉
insert into sys_menu values(1761400000000002030, '视频作品', 1761400000000002000, 3, 'work', 'heron/news/work/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsWork:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '认领后的作品：草稿、生成、版本与发布');
insert into sys_menu values(1761400000000002031, '作品查询', 1761400000000002030, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002032, '作品编辑', 1761400000000002030, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002033, '视频生成', 1761400000000002030, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:generate', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002034, '人工审片', 1761400000000002030, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:review', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002035, '审片预览', 1761400000000002030, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:reviewPreview', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002036, '成片下载', 1761400000000002030, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:download', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002037, '跨用户访问', 1761400000000002030, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:all', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '允许访问他人拥有的新闻对象');
insert into sys_menu values(1761400000000002038, '人工发布回执', 1761400000000002030, 8, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsWork:publish', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '人工在抖音发布后回填作品链接、账号与发布时间');
insert into sys_menu values(1761400000000002039, '作品工作室', 1761400000000002000, 93, 'work-studio', 'heron/news/work/studio', '', 'N', 'N', 'C', '1', '0', 'heron:newsWork:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '作品创作页，从列表带 workId 进入');

-- 4 审片中心：跨作品的待审队列
insert into sys_menu values(1761400000000002040, '审片中心', 1761400000000002000, 4, 'review', 'heron/review/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsWork:review', 'eye-open', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '跨作品的待审版本，一屏处理');

-- 5 发布记录：只列已有发布事实的版本
insert into sys_menu values(1761400000000002050, '发布记录', 1761400000000002000, 5, 'publish', 'heron/publish/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsWork:list', 'upload', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '发过的版本、回执与作品链接');

-- 6 新闻来源：配置类页面
insert into sys_menu values(1761400000000002060, '新闻来源', 1761400000000002000, 6, 'source', 'heron/news/source/index', '', 'N', 'Y', 'C', '0', '0', 'heron:newsSource:list', 'cloud', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '受白名单约束的新闻来源采集');
insert into sys_menu values(1761400000000002061, '来源查询', 1761400000000002060, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002062, '来源新增', 1761400000000002060, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002063, '来源修改', 1761400000000002060, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002064, '来源删除', 1761400000000002060, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:remove', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002065, '来源采集', 1761400000000002060, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:newsSource:fetch', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 7 数据集：注册表只读，登记走 SQL 迁移
insert into sys_menu values(1761400000000002070, '数据集', 1761400000000002000, 7, 'dataset', 'heron/asset/dataset/index', '', 'N', 'Y', 'C', '0', '0', 'heron:dataSource:list', 'chart', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '数据源注册表：授权、探测、形态与更新频率');
insert into sys_menu values(1761400000000002071, '数据集查询', 1761400000000002070, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:dataSource:list', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002072, '数据集详情', 1761400000000002070, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:dataSource:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 8 视频模板：模板是目录不是表，只读
insert into sys_menu values(1761400000000002080, '视频模板', 1761400000000002000, 8, 'template', 'heron/asset/template/index', '', 'N', 'Y', 'C', '0', '0', 'heron:template:list', 'color', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '模板目录与 LOCK 资产锁，加模板仍是加目录');
insert into sys_menu values(1761400000000002081, '模板查询', 1761400000000002080, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:template:list', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 9 生产任务：执行记录，运维视角
insert into sys_menu values(1761400000000002090, '生产任务', 1761400000000002000, 9, 'production', 'heron/production/index', '', 'N', 'Y', 'C', '0', '0', 'heron:production:list', 'list', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '统一生产任务中心');
insert into sys_menu values(1761400000000002091, '任务查询', 1761400000000002090, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:production:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002092, '任务取消', 1761400000000002090, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:production:cancel', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');

-- 10 原型工坊：另一条业务线，本次不动
insert into sys_menu values(1761400000000002100, '原型工坊', 1761400000000002000, 10, 'proto', 'heron/proto/index', '', 'N', 'Y', 'C', '0', '0', 'heron:proto:list', 'build', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '需求到 RP 原型的生成与交付');
insert into sys_menu values(1761400000000002101, '原型新增', 1761400000000002100, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:add', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002102, '原型查询', 1761400000000002100, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:query', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002103, '原型编辑', 1761400000000002100, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:edit', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002104, '原型生成', 1761400000000002100, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:generate', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002105, '原型审核', 1761400000000002100, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:review', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002106, '原型验证', 1761400000000002100, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:verify', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002107, '原型交付', 1761400000000002100, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:deliver', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002108, '原型预览', 1761400000000002100, 8, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:reviewPreview', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002109, '原型下载', 1761400000000002100, 9, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:download', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '');
insert into sys_menu values(1761400000000002110, '跨用户访问', 1761400000000002100, 10, '', '', '', 'N', 'Y', 'F', '0', '0', 'heron:proto:all', '#', '', '', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '允许访问他人拥有的原型项目');
