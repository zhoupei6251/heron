CREATE INDEX "ix_tenants_recent" ON "tenants" USING btree ("created_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_users_tenant_recent" ON "users" USING btree ("tenant_id","created_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_feed_sources_tenant_recent" ON "feed_sources" USING btree ("tenant_id","created_at" DESC,"id");--> statement-breakpoint
CREATE INDEX "ix_renders_recent" ON "renders" USING btree ("created_at" DESC,"id");