CREATE TABLE "scripts" (
	"id" uuid PRIMARY KEY NOT NULL,
	"task_id" uuid NOT NULL,
	"model" text NOT NULL,
	"content" jsonb NOT NULL,
	"created_at" text NOT NULL
);
--> statement-breakpoint
ALTER TABLE "scripts" ADD CONSTRAINT "scripts_task_id_tasks_id_fk" FOREIGN KEY ("task_id") REFERENCES "public"."tasks"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "u_scripts_task" ON "scripts" USING btree ("task_id");