CREATE TABLE "tenants" (
	"id" uuid PRIMARY KEY NOT NULL,
	"name" text NOT NULL,
	"slug" text NOT NULL,
	"suspended_at" text,
	"created_at" text NOT NULL,
	CONSTRAINT "tenants_slug_unique" UNIQUE("slug"),
	CONSTRAINT "ck_tenants_slug_lower" CHECK ("tenants"."slug" = lower("tenants"."slug"))
);
--> statement-breakpoint
CREATE TABLE "users" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"email" text NOT NULL,
	"display_name" text NOT NULL,
	"password_hash" text NOT NULL,
	"role" text DEFAULT 'viewer' NOT NULL,
	"disabled_at" text,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL,
	CONSTRAINT "ck_users_email_lower" CHECK ("users"."email" = lower("users"."email")),
	CONSTRAINT "ck_users_role_known" CHECK ("users"."role" IN ('owner','admin','reviewer','operator','viewer'))
);
--> statement-breakpoint
CREATE TABLE "platform_accounts" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"platform" text NOT NULL,
	"external_account_id" text NOT NULL,
	"auth_mode" text NOT NULL,
	"credential" text,
	"credential_path" text,
	"credential_sha256" text,
	"verified_at" text,
	"created_at" text NOT NULL,
	CONSTRAINT "ck_platform_accounts_auth_mode" CHECK ("platform_accounts"."auth_mode" IN ('oauth','cookie')),
	CONSTRAINT "ck_platform_accounts_credential_pair" CHECK (("platform_accounts"."credential_path" IS NULL) = ("platform_accounts"."credential_sha256" IS NULL))
);
--> statement-breakpoint
CREATE TABLE "candidates" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"title" text NOT NULL,
	"summary" text DEFAULT '' NOT NULL,
	"norm_key" text NOT NULL,
	"source_id" uuid,
	"heat" double precision DEFAULT 0 NOT NULL,
	"rank_peak" integer,
	"hits" integer DEFAULT 1 NOT NULL,
	"status" text DEFAULT 'candidate' NOT NULL,
	"first_seen_at" text NOT NULL,
	"last_seen_at" text NOT NULL,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL,
	CONSTRAINT "ck_candidates_hits_positive" CHECK ("candidates"."hits" > 0),
	CONSTRAINT "ck_candidates_rank_peak_positive" CHECK ("candidates"."rank_peak" IS NULL OR "candidates"."rank_peak" > 0),
	CONSTRAINT "ck_candidates_status_known" CHECK ("candidates"."status" IN ('candidate','selected','drafted','rendered','published','rejected'))
);
--> statement-breakpoint
CREATE TABLE "feed_sources" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"name" text NOT NULL,
	"url" text NOT NULL,
	"frequency_sec" integer DEFAULT 900 NOT NULL,
	"last_fetch_at" text,
	"created_at" text NOT NULL,
	CONSTRAINT "ck_feed_sources_frequency" CHECK ("feed_sources"."frequency_sec" >= 60)
);
--> statement-breakpoint
CREATE TABLE "gate_results" (
	"id" uuid PRIMARY KEY NOT NULL,
	"task_id" uuid NOT NULL,
	"gate" text NOT NULL,
	"render_version" integer,
	"passed" boolean NOT NULL,
	"note" text,
	"rejections" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"evaluated_at" text NOT NULL,
	CONSTRAINT "ck_gate_results_gate_known" CHECK ("gate_results"."gate" IN ('dedupe','contract','visual','compliance','human')),
	CONSTRAINT "ck_gate_results_render_version_positive" CHECK ("gate_results"."render_version" IS NULL OR "gate_results"."render_version" > 0)
);
--> statement-breakpoint
CREATE TABLE "settings" (
	"tenant_id" uuid NOT NULL,
	"key" text NOT NULL,
	"value" text NOT NULL,
	"updated_at" text NOT NULL,
	CONSTRAINT "settings_tenant_id_key_pk" PRIMARY KEY("tenant_id","key")
);
--> statement-breakpoint
CREATE TABLE "task_events" (
	"id" integer PRIMARY KEY GENERATED ALWAYS AS IDENTITY (sequence name "task_events_id_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 2147483647 START WITH 1 CACHE 1),
	"task_id" uuid NOT NULL,
	"from_stage" text NOT NULL,
	"to_stage" text NOT NULL,
	"reason" text,
	"created_at" text NOT NULL,
	CONSTRAINT "ck_task_events_from_stage" CHECK ("task_events"."from_stage" IN ('candidate','claimed','scripting','factcheck','rendering','review','approved','publishing','published','failed')),
	CONSTRAINT "ck_task_events_to_stage" CHECK ("task_events"."to_stage" IN ('candidate','claimed','scripting','factcheck','rendering','review','approved','publishing','published','failed'))
);
--> statement-breakpoint
CREATE TABLE "tasks" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"capability" text NOT NULL,
	"candidate_id" uuid,
	"stage" text NOT NULL,
	"lease_owner" text,
	"heartbeat" text,
	"attempts" integer DEFAULT 0 NOT NULL,
	"claimed_by" uuid,
	"claimed_at" text,
	"error_reason" text,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL,
	CONSTRAINT "ck_tasks_stage_known" CHECK ("tasks"."stage" IN ('candidate','claimed','scripting','factcheck','rendering','review','approved','publishing','published','failed')),
	CONSTRAINT "ck_tasks_attempts_nonnegative" CHECK ("tasks"."attempts" >= 0)
);
--> statement-breakpoint
CREATE TABLE "aigc_posture" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"render_mode" text NOT NULL,
	"declaration" text NOT NULL,
	"note" text DEFAULT '' NOT NULL,
	"set_by" uuid NOT NULL,
	"since_at" text NOT NULL,
	"reverted_at" text,
	CONSTRAINT "ck_aigc_posture_render_mode" CHECK ("aigc_posture"."render_mode" IN ('full','no-badge','draft')),
	CONSTRAINT "ck_aigc_posture_declaration" CHECK ("aigc_posture"."declaration" IN ('required','undeclared'))
);
--> statement-breakpoint
CREATE TABLE "confirmations" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"task_id" uuid NOT NULL,
	"render_version" integer NOT NULL,
	"contact_sheet_fingerprint" text NOT NULL,
	"confirmed_by" uuid NOT NULL,
	"confirmed_at" text NOT NULL,
	CONSTRAINT "ck_confirmations_render_version_positive" CHECK ("confirmations"."render_version" > 0)
);
--> statement-breakpoint
CREATE TABLE "publishes" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"render_id" uuid NOT NULL,
	"platform" text DEFAULT 'douyin' NOT NULL,
	"idempotency_key" text NOT NULL,
	"declaration" text NOT NULL,
	"credential_path" text,
	"credential_sha256" text,
	"status" text NOT NULL,
	"post_url" text,
	"receipt" text,
	"verified_at" text,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL,
	CONSTRAINT "ck_publishes_status_known" CHECK ("publishes"."status" IN ('pending','sent','confirmed','failed')),
	CONSTRAINT "ck_publishes_credential_pair" CHECK (("publishes"."credential_path" IS NULL) = ("publishes"."credential_sha256" IS NULL))
);
--> statement-breakpoint
CREATE TABLE "renders" (
	"id" uuid PRIMARY KEY NOT NULL,
	"task_id" uuid NOT NULL,
	"version" integer NOT NULL,
	"pack" text NOT NULL,
	"mp4_path" text,
	"mp4_sha256" text,
	"mp4_bytes" integer,
	"contact_sheet_path" text,
	"fingerprint" text NOT NULL,
	"width" integer,
	"height" integer,
	"fps" double precision,
	"duration_ms" integer,
	"aigc_sidecar_path" text,
	"timings" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"created_at" text NOT NULL,
	CONSTRAINT "ck_renders_version_positive" CHECK ("renders"."version" > 0),
	CONSTRAINT "ck_renders_bytes_nonnegative" CHECK ("renders"."mp4_bytes" IS NULL OR "renders"."mp4_bytes" >= 0),
	CONSTRAINT "ck_renders_width_positive" CHECK ("renders"."width" IS NULL OR "renders"."width" > 0),
	CONSTRAINT "ck_renders_height_positive" CHECK ("renders"."height" IS NULL OR "renders"."height" > 0),
	CONSTRAINT "ck_renders_fps_positive" CHECK ("renders"."fps" IS NULL OR "renders"."fps" > 0),
	CONSTRAINT "ck_renders_duration_nonnegative" CHECK ("renders"."duration_ms" IS NULL OR "renders"."duration_ms" >= 0)
);
--> statement-breakpoint
CREATE TABLE "usage_records" (
	"id" bigint PRIMARY KEY GENERATED ALWAYS AS IDENTITY (sequence name "usage_records_id_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1),
	"tenant_id" uuid NOT NULL,
	"task_id" uuid,
	"kind" text NOT NULL,
	"quantity" numeric NOT NULL,
	"unit" text NOT NULL,
	"detail" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"recorded_at" text NOT NULL,
	CONSTRAINT "ck_usage_records_kind_known" CHECK ("usage_records"."kind" IN ('llm_tokens_in','llm_tokens_out','image_gen','render_seconds','publish')),
	CONSTRAINT "ck_usage_records_unit_known" CHECK ("usage_records"."unit" IN ('tokens','count','seconds')),
	CONSTRAINT "ck_usage_records_kind_unit_pair" CHECK (CASE "usage_records"."kind" WHEN 'llm_tokens_in' THEN "usage_records"."unit" = 'tokens' WHEN 'llm_tokens_out' THEN "usage_records"."unit" = 'tokens' WHEN 'image_gen' THEN "usage_records"."unit" = 'count' WHEN 'render_seconds' THEN "usage_records"."unit" = 'seconds' WHEN 'publish' THEN "usage_records"."unit" = 'count' END)
);
--> statement-breakpoint
ALTER TABLE "users" ADD CONSTRAINT "users_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "platform_accounts" ADD CONSTRAINT "platform_accounts_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "candidates" ADD CONSTRAINT "candidates_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "candidates" ADD CONSTRAINT "candidates_source_id_feed_sources_id_fk" FOREIGN KEY ("source_id") REFERENCES "public"."feed_sources"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "feed_sources" ADD CONSTRAINT "feed_sources_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "gate_results" ADD CONSTRAINT "gate_results_task_id_tasks_id_fk" FOREIGN KEY ("task_id") REFERENCES "public"."tasks"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "settings" ADD CONSTRAINT "settings_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "task_events" ADD CONSTRAINT "task_events_task_id_tasks_id_fk" FOREIGN KEY ("task_id") REFERENCES "public"."tasks"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "tasks" ADD CONSTRAINT "tasks_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "tasks" ADD CONSTRAINT "tasks_candidate_id_candidates_id_fk" FOREIGN KEY ("candidate_id") REFERENCES "public"."candidates"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "tasks" ADD CONSTRAINT "tasks_claimed_by_users_id_fk" FOREIGN KEY ("claimed_by") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "aigc_posture" ADD CONSTRAINT "aigc_posture_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "aigc_posture" ADD CONSTRAINT "aigc_posture_set_by_users_id_fk" FOREIGN KEY ("set_by") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "confirmations" ADD CONSTRAINT "confirmations_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "confirmations" ADD CONSTRAINT "confirmations_task_id_tasks_id_fk" FOREIGN KEY ("task_id") REFERENCES "public"."tasks"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "confirmations" ADD CONSTRAINT "confirmations_confirmed_by_users_id_fk" FOREIGN KEY ("confirmed_by") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "publishes" ADD CONSTRAINT "publishes_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "publishes" ADD CONSTRAINT "publishes_render_id_renders_id_fk" FOREIGN KEY ("render_id") REFERENCES "public"."renders"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "renders" ADD CONSTRAINT "renders_task_id_tasks_id_fk" FOREIGN KEY ("task_id") REFERENCES "public"."tasks"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "usage_records" ADD CONSTRAINT "usage_records_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "usage_records" ADD CONSTRAINT "usage_records_task_id_tasks_id_fk" FOREIGN KEY ("task_id") REFERENCES "public"."tasks"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "u_users_tenant_email" ON "users" USING btree ("tenant_id","email");--> statement-breakpoint
CREATE UNIQUE INDEX "u_platform_accounts_external" ON "platform_accounts" USING btree ("tenant_id","platform","external_account_id");--> statement-breakpoint
CREATE INDEX "ix_platform_accounts_tenant" ON "platform_accounts" USING btree ("tenant_id","platform");--> statement-breakpoint
CREATE UNIQUE INDEX "u_candidates_tenant_norm" ON "candidates" USING btree ("tenant_id","norm_key");--> statement-breakpoint
CREATE INDEX "ix_candidates_status" ON "candidates" USING btree ("status","last_seen_at" DESC);--> statement-breakpoint
CREATE UNIQUE INDEX "u_feed_sources_tenant_url" ON "feed_sources" USING btree ("tenant_id","url");--> statement-breakpoint
CREATE INDEX "ix_gate_results_task" ON "gate_results" USING btree ("task_id","gate","evaluated_at");--> statement-breakpoint
CREATE INDEX "ix_task_events_task" ON "task_events" USING btree ("task_id","id");--> statement-breakpoint
CREATE INDEX "ix_tasks_claimable" ON "tasks" USING btree ("stage","lease_owner","heartbeat") WHERE "tasks"."stage" NOT IN ('published','failed');--> statement-breakpoint
CREATE INDEX "ix_tasks_candidate" ON "tasks" USING btree ("candidate_id");--> statement-breakpoint
CREATE UNIQUE INDEX "u_aigc_posture_single_current" ON "aigc_posture" USING btree ("tenant_id") WHERE "aigc_posture"."reverted_at" IS NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "u_confirmations_render_fingerprint" ON "confirmations" USING btree ("task_id","render_version","contact_sheet_fingerprint");--> statement-breakpoint
CREATE UNIQUE INDEX "u_publishes_tenant_idempotency" ON "publishes" USING btree ("tenant_id","idempotency_key");--> statement-breakpoint
CREATE INDEX "ix_publishes_status" ON "publishes" USING btree ("status","created_at");--> statement-breakpoint
CREATE UNIQUE INDEX "u_renders_task_version" ON "renders" USING btree ("task_id","version");--> statement-breakpoint
CREATE INDEX "ix_usage_records_tenant_time" ON "usage_records" USING btree ("tenant_id","recorded_at");