<script setup lang="ts">
import type { Asset } from "@heron/contracts";
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElTable,
  ElTableColumn,
  ElTag,
} from "element-plus";
import { onMounted, reactive, ref } from "vue";
import { artifactUrl } from "../../api/reviews";
import ListPanel from "../../components/ListPanel.vue";
import { usePagedList } from "../../composables/usePagedList";
import { dateTime } from "../../format";
const list = reactive(usePagedList<Asset>("/manage/assets"));
const drawer = ref(false);
const selected = ref<Asset | null>(null);
const mediaError = ref(false);
function inspect(row: Asset) {
  selected.value = row;
  mediaError.value = false;
  drawer.value = true;
}
onMounted(list.load);
</script>
<template>
  <section>
    <header class="page-header">
      <div>
        <span class="eyebrow">Content / asset library</span>
        <h1>成片库</h1>
        <p class="page-description">
          集中查看视频、联络表与合规文件。每份产物都有明确的版本和指纹。
        </p>
      </div>
    </header>
    <ListPanel
      v-model:query="list.query"
      v-model:page="list.page"
      v-model:page-size="list.pageSize"
      :total="list.total"
      :loading="list.loading"
      :error="list.error"
      placeholder="搜索成片标题"
      empty-title="成片库还是空的"
      empty-hint="任务通过审片确认后会在这里留下版本记录，并按保留天数自动清理。"
      @search="list.search"
      @refresh="list.load"
    >
      <ElTable :data="list.items" row-key="id">
        <ElTableColumn label="成片" min-width="300"
          ><template #default="{ row }"
            ><div class="cell-title">{{ row.title ?? "未命名成片" }}</div>
            <div class="cell-subtitle">{{ row.pack }} · 版本 {{ row.version }}</div></template
          ></ElTableColumn
        >
        <ElTableColumn label="规格" min-width="150"
          ><template #default="{ row }"
            ><span class="mono">{{
              row.width && row.height ? `${row.width} × ${row.height}` : "尚无规格"
            }}</span>
            <div class="cell-subtitle">
              {{
                row.durationMs !== null ? `${(row.durationMs / 1000).toFixed(1)} 秒` : "时长未知"
              }}
            </div></template
          ></ElTableColumn
        >
        <ElTableColumn label="视频文件" width="130"
          ><template #default="{ row }"
            ><ElTag :type="row.hasMp4 ? 'success' : 'warning'">{{
              row.hasMp4 ? "已记录" : "缺失"
            }}</ElTag></template
          ></ElTableColumn
        >
        <ElTableColumn label="生成时间" min-width="170"
          ><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="100" fixed="right"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="inspect(row as Asset)">查看</ElButton></template
          ></ElTableColumn
        >
      </ElTable>
    </ListPanel>
    <p class="form-hint">
      历史版本保留元数据；在线文件只提供任务的最新版本。若版本已变化或文件已清理，请刷新后查看最新成片。
    </p>
    <ElDrawer v-model="drawer" title="成片与文件" size="700px" destroy-on-close
      ><template v-if="selected">
        <h2>
          {{ selected.title ?? "未命名成片" }} <ElTag>版本 {{ selected.version }}</ElTag>
        </h2>
        <ElAlert
          v-if="mediaError"
          title="文件无法加载，可能已被清理或不再是最新版本。关闭并刷新列表后重试，不会自动替换为另一版文件。"
          type="warning"
          :closable="false"
          class="page-alert"
        />
        <video
          v-if="selected.hasMp4"
          :key="`${selected.id}-video`"
          :src="artifactUrl(selected.taskId, 'mp4', selected.version)"
          controls
          preload="metadata"
          class="asset-video"
          @error="mediaError = true"
        />
        <ElAlert
          v-else
          title="此版本未记录视频文件。"
          type="info"
          :closable="false"
          class="page-alert"
        />
        <img
          v-if="selected.hasContactSheet"
          :key="`${selected.id}-sheet`"
          :src="artifactUrl(selected.taskId, 'contact-sheet', selected.version)"
          alt="成片联络表"
          class="asset-sheet"
          @error="mediaError = true"
        />
        <div class="form-actions" style="margin-bottom: 24px">
          <a
            v-if="selected.hasMp4"
            :href="artifactUrl(selected.taskId, 'mp4', selected.version)"
            :download="`${selected.taskId}-v${selected.version}.mp4`"
            ><ElButton>下载视频</ElButton></a
          ><a
            v-if="selected.hasAigcSidecar"
            :href="artifactUrl(selected.taskId, 'aigc-sidecar', selected.version)"
            download
            ><ElButton>下载合规文件</ElButton></a
          >
        </div>
        <ElDescriptions :column="1" border
          ><ElDescriptionsItem label="版本指纹"
            ><span class="mono" style="overflow-wrap: anywhere">{{
              selected.fingerprint
            }}</span></ElDescriptionsItem
          ><ElDescriptionsItem label="视频 SHA-256"
            ><span class="mono" style="overflow-wrap: anywhere">{{
              selected.mp4Sha256 ?? "未记录"
            }}</span></ElDescriptionsItem
          ><ElDescriptionsItem label="文件字节数">{{
            selected.mp4Bytes ?? "未记录"
          }}</ElDescriptionsItem
          ><ElDescriptionsItem label="帧率">{{ selected.fps ?? "未记录" }}</ElDescriptionsItem
          ><ElDescriptionsItem label="生成时间">{{
            dateTime(selected.createdAt)
          }}</ElDescriptionsItem></ElDescriptions
        >
      </template></ElDrawer
    >
  </section>
</template>
<style scoped>
.asset-video {
  width: 100%;
  max-height: 380px;
  background: var(--heron-stage);
  border-radius: 12px;
  margin-bottom: 20px;
}
.asset-sheet {
  display: block;
  max-width: 100%;
  border: 1px solid var(--heron-line);
  border-radius: 10px;
}
</style>
