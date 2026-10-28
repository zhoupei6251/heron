DROP INDEX "ix_candidates_status";--> statement-breakpoint
DROP INDEX "ix_publishes_status";--> statement-breakpoint
DROP INDEX "ix_tasks_claimable";--> statement-breakpoint
DROP INDEX "ix_audit_logs_tenant_time";--> statement-breakpoint
DROP INDEX "ix_platform_audit_logs_time";--> statement-breakpoint
CREATE INDEX "ix_candidates_tenant_recent" ON "candidates" USING btree ("tenant_id","last_seen_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_tasks_tenant_recent" ON "tasks" USING btree ("tenant_id","created_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_publishes_tenant_recent" ON "publishes" USING btree ("tenant_id","created_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_tasks_claimable" ON "tasks" USING btree ("created_at") WHERE "tasks"."stage" NOT IN ('published','failed');--> statement-breakpoint
CREATE INDEX "ix_audit_logs_tenant_time" ON "audit_logs" USING btree ("tenant_id","created_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_platform_audit_logs_time" ON "platform_audit_logs" USING btree ("created_at" DESC,"id");