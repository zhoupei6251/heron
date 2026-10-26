ALTER TABLE "users" ADD COLUMN "session_version" integer DEFAULT 0 NOT NULL;
--> statement-breakpoint
ALTER TABLE "feed_sources" ADD COLUMN "enabled" boolean DEFAULT true NOT NULL;
--> statement-breakpoint
CREATE TABLE "audit_logs" (
  "id" uuid PRIMARY KEY NOT NULL,
  "tenant_id" uuid NOT NULL CONSTRAINT "audit_logs_tenant_id_tenants_id_fk" REFERENCES "tenants"("id") ON DELETE restrict,
  "actor_id" uuid CONSTRAINT "audit_logs_actor_id_users_id_fk" REFERENCES "users"("id") ON DELETE restrict,
  "action" text NOT NULL,
  "target_id" text,
  "detail" jsonb DEFAULT '{}'::jsonb NOT NULL,
  "created_at" text NOT NULL
);
--> statement-breakpoint
CREATE INDEX "ix_audit_logs_tenant_time" ON "audit_logs" ("tenant_id", "created_at", "id");
--> statement-breakpoint
CREATE TABLE "platform_operators" (
  "id" uuid PRIMARY KEY NOT NULL,
  "email" text NOT NULL,
  "display_name" text NOT NULL,
  "password_hash" text NOT NULL,
  "session_version" integer DEFAULT 0 NOT NULL,
  "disabled_at" text,
  "created_at" text NOT NULL,
  CONSTRAINT "ck_platform_operators_email_normalized" CHECK ("email" = lower(btrim("email")))
);
--> statement-breakpoint
CREATE UNIQUE INDEX "u_platform_operators_email" ON "platform_operators" ("email");
--> statement-breakpoint
CREATE TABLE "platform_audit_logs" (
  "id" uuid PRIMARY KEY NOT NULL,
  "operator_id" uuid CONSTRAINT "platform_audit_logs_operator_id_platform_operators_id_fk" REFERENCES "platform_operators"("id") ON DELETE restrict,
  "action" text NOT NULL,
  "target_id" text,
  "detail" jsonb DEFAULT '{}'::jsonb NOT NULL,
  "created_at" text NOT NULL
);
--> statement-breakpoint
CREATE INDEX "ix_platform_audit_logs_time" ON "platform_audit_logs" ("created_at", "id");
--> statement-breakpoint
-- Also close any grants inherited from deployment default privileges before applyRls runs.
REVOKE ALL ON "platform_operators", "platform_audit_logs" FROM PUBLIC;
--> statement-breakpoint
REVOKE DELETE, TRUNCATE ON "users", "tenants" FROM PUBLIC;
--> statement-breakpoint
DO $do$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'heron_app') THEN
    REVOKE ALL ON "platform_operators", "platform_audit_logs" FROM heron_app;
    REVOKE DELETE, TRUNCATE ON "users", "tenants" FROM heron_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'heron_owner') THEN
    REVOKE DELETE, TRUNCATE ON "users", "tenants", "platform_operators" FROM heron_owner;
  END IF;
END $do$;
