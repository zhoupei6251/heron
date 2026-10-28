import type { Page } from "@heron/contracts";
import type { LocationQuery } from "vue-router";
import { computed, onScopeDispose, ref, shallowRef, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { errorMessage, request } from "../api/http";

const PAGE_SIZES = [10, 20, 50];
const FIRST_PAGE = 1;
const DEFAULT_SIZE = 20;

/**
 * 页、页大小、搜索词都在地址栏里：刷新、分享链接、后退都回到同一页同一搜索。
 * 地址栏是唯一真相——用户动作只写 URL，URL 变化才拉数据；写不动（同一页同一词）时由调用方补一次。
 */
export function usePagedList<T>(path: string, allowed = () => true) {
  const route = useRoute();
  const router = useRouter();
  const here = route.path;
  const page = computed<number>({
    get: () => positive(route.query.page, FIRST_PAGE),
    set: (value) => void write({ page: value === FIRST_PAGE ? undefined : String(value) }),
  });
  const pageSize = computed<number>({
    get: () =>
      PAGE_SIZES.includes(Number(route.query.pageSize))
        ? Number(route.query.pageSize)
        : DEFAULT_SIZE,
    set: (value) =>
      void write({ pageSize: value === DEFAULT_SIZE ? undefined : String(value), page: undefined }),
  });
  /** 输入框里的草稿，按下搜索才进地址栏。 */
  const query = ref(text(route.query.q));
  const items = shallowRef<T[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const error = ref<string | null>(null);
  let appliedQuery = query.value;
  let generation = 0;

  /** 只写非默认值：第一页第一档不该在地址栏里留下三条空参数。返回 false 表示地址没变。 */
  async function write(patch: Record<string, string | undefined>): Promise<boolean> {
    const next: LocationQuery = { ...route.query };
    for (const [key, value] of Object.entries(patch)) {
      if (value === undefined) delete next[key];
      else next[key] = value;
    }
    if (!differs(next, route.query)) return false;
    await router.replace({ query: next });
    return true;
  }

  async function load(): Promise<void> {
    const current = ++generation;
    if (!allowed()) {
      items.value = [];
      total.value = 0;
      loading.value = false;
      return;
    }
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({
        page: String(page.value),
        pageSize: String(pageSize.value),
        q: appliedQuery,
      });
      const result = await request<Page<T>>(`${path}?${params}`);
      if (current !== generation) return;
      items.value = result.items;
      total.value = result.total;
    } catch (cause) {
      if (current !== generation) return;
      items.value = [];
      total.value = 0;
      error.value = errorMessage(cause);
    } finally {
      if (current === generation) loading.value = false;
    }
  }

  async function search(): Promise<void> {
    const term = query.value.trim();
    query.value = term;
    // 同一页同一词写不动地址，这时没人替我们拉，自己拉
    if (!(await write({ q: term || undefined, page: undefined }))) await load();
  }

  watch(
    () => route.query,
    () => {
      // 离开本页时路由也会变，别拿新页的查询参数去查旧页的列表
      if (route.path !== here) return;
      appliedQuery = text(route.query.q);
      void load();
    },
  );
  onScopeDispose(() => {
    generation++;
  });
  return { items, total, page, pageSize, query, loading, error, load, search };
}

function text(value: LocationQuery[string] | undefined): string {
  return typeof value === "string" ? value : "";
}

function positive(value: LocationQuery[string] | undefined, fallback: number): number {
  const parsed = Number(value);
  return typeof value === "string" && Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function differs(next: LocationQuery, current: LocationQuery): boolean {
  const keys = new Set([...Object.keys(next), ...Object.keys(current)]);
  return [...keys].some((key) => text(next[key]) !== text(current[key]));
}
