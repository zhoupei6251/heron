import type { Page } from "@heron/contracts";
import { onScopeDispose, ref, shallowRef, watch } from "vue";
import { errorMessage, request } from "../api/http";

export function usePagedList<T>(path: string, allowed = () => true) {
  const items = shallowRef<T[]>([]);
  const total = ref(0);
  const page = ref(1);
  const pageSize = ref(20);
  const query = ref("");
  const loading = ref(false);
  const error = ref<string | null>(null);
  let appliedQuery = "";
  let generation = 0;
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
    appliedQuery = query.value.trim();
    if (page.value !== 1) page.value = 1;
    else await load();
  }
  watch([page, pageSize], () => {
    void load();
  });
  onScopeDispose(() => {
    generation++;
  });
  return { items, total, page, pageSize, query, loading, error, load, search };
}
