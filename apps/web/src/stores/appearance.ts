import { useColorMode, useStorage, type BasicColorSchema } from "@vueuse/core";
import { defineStore } from "pinia";
import { ref } from "vue";

export const useAppearanceStore = defineStore("appearance", () => {
  const persistent = ref(true);
  // useColorMode does not forward storage serializers or errors to useStorage.
  const mode = useStorage<BasicColorSchema>("heron:appearance", "auto", undefined, {
    serializer: {
      read: (value: string): BasicColorSchema =>
        value === "light" || value === "dark" ? value : "auto",
      write: (value: BasicColorSchema) => value,
    },
    onError: () => {
      persistent.value = false;
    },
  });
  const { state: resolved } = useColorMode({ storageRef: mode });
  return { mode, resolved, persistent };
});
