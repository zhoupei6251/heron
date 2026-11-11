-- 第二批独立源：HuggingFace、Jikan 番剧/漫画、CheapShark 折扣。
-- 幂等迁移由授权环境执行，本文件不在现有 heron 库直接运行。
INSERT INTO heron_data_source
(source_key,name,category,endpoint,history_endpoint,http_method,data_format,cadence,history_days,commercial_ok,terms_url,shapes,enabled,probe_status,probe_note,create_time,update_time)
VALUES
('huggingface-trending','HuggingFace·趋势模型','ai',
 'https://huggingface.co/api/models?sort=trendingScore',
 NULL,'GET','json','daily',NULL,'unknown','https://huggingface.co/terms-of-service',
 'bars','0','ok',
 '免 key；免登录限流较紧，按日一调用',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('jikan-top-anime','MyAnimeList·番剧榜','entertainment',
 'https://api.jikan.moe/v4/top/anime',
 NULL,'GET','json','daily',NULL,'unknown','https://docs.api.jikan.moe/',
 'bars','0','ok',
 '免 key；非官方镜像，限流约 3 次/秒',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('jikan-top-manga','MyAnimeList·漫画榜','entertainment',
 'https://api.jikan.moe/v4/top/manga',
 NULL,'GET','json','daily',NULL,'unknown','https://docs.api.jikan.moe/',
 'bars','0','ok',
 '免 key；与番剧榜共用同一个上游',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),
('cheapshark-deals','CheapShark·游戏折扣','gaming',
 'https://www.cheapshark.com/api/1.0/deals',
 NULL,'GET','json','daily',NULL,'unknown','https://www.cheapshark.com/api',
 'bars','0','ok',
 '免 key；Steam 折扣按 Deal Rating 排序',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
