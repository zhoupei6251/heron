<script setup lang="ts">
import { can, requiredRoleFor } from "@heron/domain";
import { ElAlert, ElButton, ElMessage, ElTooltip } from "element-plus";
import { computed, onMounted } from "vue";

import { useAuthStore } from "../stores/auth";
import { useGatesStore } from "../stores/gates";

const auth = useAuthStore();
const gates = useGatesStore();
onMounted(() => void gates.refresh());

/**
 * 置灰有两个独立原因，文案必须说清是哪一个：
 * 角色不够是「你不该点」，闸没开是「现在不能点」。混成一句会让人去找管理员要权限，
 * 而真正缺的是闸门。判据本身来自 domain 的 can()，界面不重写第二份。
 */
const roleAllowed = computed(() => (auth.user ? can(auth.user.role, "review.confirm") : false));
const confirmHint = computed(() => {
  if (!roleAllowed.value) return `需要 ${requiredRoleFor("review.confirm")} 及以上`;
  if (!gates.state?.open) return "门禁位未全开：闸门点不动才算闸门";
  return "";
});

function confirm(): void {
  ElMessage.warning("确认闸需要绑定联络表版本指纹，P2 落地");
}
</script>

<template>
  <section>
    <h2 class="mb-3 text-base font-semibold">审片台</h2>

    <ElAlert
      v-if="gates.state && !gates.state.open"
      type="warning"
      :closable="false"
      class="mb-4"
      :title="`门禁位未全开：${gates.state.blockers.length} 道闸未启用`"
    >
      <template #default>
        <ul class="list-disc pl-5 text-xs">
          <li v-for="blocker in gates.state.blockers" :key="blocker">{{ blocker }}</li>
        </ul>
      </template>
    </ElAlert>

    <div class="flex gap-6">
      <iframe src="/host.html?comp=demo" class="h-[720px] w-[405px] border border-neutral-300" />
      <div class="max-w-md">
        <p class="mb-3 text-sm text-neutral-600">
          预览走 <code>host.html?comp=demo</code> 的 iframe：版式产物与应用样式互不进入。
        </p>
        <ElTooltip :content="confirmHint" :disabled="confirmHint === ''">
          <span class="inline-block">
            <ElButton
              type="primary"
              :disabled="!roleAllowed || !gates.state?.open"
              @click="confirm"
            >
              确认
            </ElButton>
          </span>
        </ElTooltip>
        <p v-if="confirmHint" class="mt-2 text-xs text-neutral-500">{{ confirmHint }}</p>
      </div>
    </div>
  </section>
</template>
