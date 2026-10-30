INSERT INTO heron_production_task (
  task_id,biz_type,biz_id,version_id,owner_id,actor_id,
  request_key,request_hash,input_hash,input_snapshot,
  status,stage,run_token,
  create_dept,create_by,create_time,update_by,update_time
) VALUES (
  100,'prototype',10,1001,1,1,
  'fixture-key',REPEAT('a',64),REPEAT('b',64),'{}',
  'queued',NULL,NULL,
  NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3)
);

INSERT INTO heron_usage_record (
  usage_id,task_id,attempt_no,call_id,stage,provider,model,provider_call_id,
  status,input_tokens,output_tokens,elapsed_ms,error_code,
  create_dept,create_by,create_time,update_by,update_time
) VALUES (
  101,100,0,'00000000-0000-0000-0000-000000000101','scripting',NULL,NULL,NULL,
  'started',NULL,NULL,NULL,NULL,
  NULL,NULL,UTC_TIMESTAMP(3),NULL,UTC_TIMESTAMP(3)
);
