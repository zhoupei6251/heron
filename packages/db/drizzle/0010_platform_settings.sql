CREATE TABLE "platform_settings" (
	"key" text PRIMARY KEY NOT NULL,
	"value" text NOT NULL,
	"updated_at" text NOT NULL,
	"updated_by" uuid
);
--> statement-breakpoint
ALTER TABLE "platform_settings" ADD CONSTRAINT "platform_settings_updated_by_platform_operators_id_fk" FOREIGN KEY ("updated_by") REFERENCES "public"."platform_operators"("id") ON DELETE restrict ON UPDATE no action;

--> statement-breakpoint
-- 平台设置同样是「只走特权通道」：部署默认权限可能给 PUBLIC / heron_app 留了口子，建表当场收回。
REVOKE ALL ON "platform_settings" FROM PUBLIC;
--> statement-breakpoint
DO $do$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'heron_app') THEN
    REVOKE ALL ON "platform_settings" FROM heron_app;
  END IF;
END $do$;
