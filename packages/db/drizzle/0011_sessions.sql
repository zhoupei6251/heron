CREATE TABLE "sessions" (
	"id" uuid PRIMARY KEY NOT NULL,
	"tenant_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	"created_at" text NOT NULL,
	"last_seen_at" text NOT NULL,
	"user_agent" text,
	"revoked_at" text
);
--> statement-breakpoint
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_tenant_id_tenants_id_fk" FOREIGN KEY ("tenant_id") REFERENCES "public"."tenants"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "ix_sessions_tenant_recent" ON "sessions" USING btree ("tenant_id","created_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_sessions_tenant_user" ON "sessions" USING btree ("tenant_id","user_id","last_seen_at" DESC);