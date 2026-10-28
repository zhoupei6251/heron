CREATE TABLE "proto_projects" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"title" text NOT NULL,
	"requirement" text NOT NULL,
	"target" text NOT NULL,
	"theme" text NOT NULL,
	"stage" text NOT NULL,
	"error_reason" text,
	"lease_owner" text,
	"heartbeat" text,
	"attempts" integer DEFAULT 0 NOT NULL,
	"created_by" uuid,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL,
	CONSTRAINT "ck_proto_projects_stage_known" CHECK ("proto_projects"."stage" IN ('draft','scripting','compiling','review','approved','delivered','failed')),
	CONSTRAINT "ck_proto_projects_target_known" CHECK ("proto_projects"."target" IN ('web','mobile')),
	CONSTRAINT "ck_proto_projects_theme_known" CHECK ("proto_projects"."theme" IN ('enterprise-light','modern-dark','cobalt-pro','mobile-lowfi','mobile-hifi')),
	CONSTRAINT "ck_proto_projects_attempts_nonnegative" CHECK ("proto_projects"."attempts" >= 0)
);
--> statement-breakpoint
CREATE TABLE "proto_revisions" (
	"id" uuid PRIMARY KEY NOT NULL,
	"project_id" uuid NOT NULL,
	"version" integer NOT NULL,
	"rp1_source" text,
	"engine_version" text,
	"audit" jsonb,
	"model" text,
	"tokens_in" integer,
	"tokens_out" integer,
	"failure_kind" text,
	"failure_reason" text,
	"rp_sha256" text,
	"rp_bytes" integer,
	"preview_sha256" text,
	"confirmed_by" uuid,
	"confirmed_at" text,
	"axure_verified" boolean DEFAULT false NOT NULL,
	"axure_verified_by" uuid,
	"axure_verified_at" text,
	"delivered_at" text,
	"created_at" text NOT NULL,
	CONSTRAINT "ck_proto_revisions_version_positive" CHECK ("proto_revisions"."version" > 0),
	CONSTRAINT "ck_proto_revisions_tokens_nonnegative" CHECK (("proto_revisions"."tokens_in" IS NULL OR "proto_revisions"."tokens_in" >= 0) AND ("proto_revisions"."tokens_out" IS NULL OR "proto_revisions"."tokens_out" >= 0)),
	CONSTRAINT "ck_proto_revisions_rp_bytes_nonnegative" CHECK ("proto_revisions"."rp_bytes" IS NULL OR "proto_revisions"."rp_bytes" >= 0),
	CONSTRAINT "ck_proto_revisions_failure_kind_known" CHECK ("proto_revisions"."failure_kind" IN ('model','contract','visual','engine')),
	CONSTRAINT "ck_proto_revisions_axure_attribution" CHECK (("proto_revisions"."axure_verified" = false AND "proto_revisions"."axure_verified_by" IS NULL AND "proto_revisions"."axure_verified_at" IS NULL)
          OR ("proto_revisions"."axure_verified" = true AND "proto_revisions"."axure_verified_by" IS NOT NULL AND "proto_revisions"."axure_verified_at" IS NOT NULL))
);
--> statement-breakpoint
ALTER TABLE "proto_projects" ADD CONSTRAINT "proto_projects_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "proto_projects" ADD CONSTRAINT "proto_projects_created_by_users_id_fk" FOREIGN KEY ("created_by") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "proto_revisions" ADD CONSTRAINT "proto_revisions_project_id_proto_projects_id_fk" FOREIGN KEY ("project_id") REFERENCES "public"."proto_projects"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "proto_revisions" ADD CONSTRAINT "proto_revisions_confirmed_by_users_id_fk" FOREIGN KEY ("confirmed_by") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "proto_revisions" ADD CONSTRAINT "proto_revisions_axure_verified_by_users_id_fk" FOREIGN KEY ("axure_verified_by") REFERENCES "public"."users"("id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "ix_proto_projects_claimable" ON "proto_projects" USING btree ("created_at") WHERE "proto_projects"."stage" IN ('draft','approved');--> statement-breakpoint
CREATE INDEX "ix_proto_projects_tenant_recent" ON "proto_projects" USING btree ("tenant_id","created_at" DESC,"id");--> statement-breakpoint
CREATE UNIQUE INDEX "u_proto_revisions_project_version" ON "proto_revisions" USING btree ("project_id","version");--> statement-breakpoint
CREATE INDEX "ix_proto_revisions_project" ON "proto_revisions" USING btree ("project_id","version");