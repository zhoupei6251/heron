import { defineConfig } from "drizzle-kit";

export default defineConfig({
  dialect: "postgresql",
  schema: "./src/pg/schema/index.ts",
  out: "./drizzle",
});
