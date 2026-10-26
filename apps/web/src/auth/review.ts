import type { GateState, ReviewItem, Role } from "@heron/contracts";
import { can } from "@heron/domain";

export function reviewBlocker(
  role: Role | null,
  gates: GateState | null,
  item: ReviewItem | null,
  sheetLoaded: boolean,
  acknowledged: boolean,
): string {
  if (!role || !can(role, "review.confirm")) return "需要审核员或更高权限";
  if (!gates?.open) return "发布闸门尚未全部开放，暂时不能确认";
  if (!item?.renderVersion || !item.contactSheetFingerprint) return "当前成片没有可确认的版本指纹";
  if (!sheetLoaded) return "请先加载并检查当前版本的联络表";
  if (!acknowledged) return "请勾选确认已检查成片内容";
  return "";
}
