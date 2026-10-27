ALTER TABLE "platform_operators" RENAME COLUMN "email" TO "username";--> statement-breakpoint
ALTER INDEX "u_platform_operators_email" RENAME TO "u_platform_operators_username";--> statement-breakpoint
ALTER TABLE "platform_operators" RENAME CONSTRAINT "ck_platform_operators_email_normalized" TO "ck_platform_operators_username_normalized";
