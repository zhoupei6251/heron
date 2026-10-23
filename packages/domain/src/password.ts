import { randomBytes, scryptSync, timingSafeEqual } from "node:crypto";

/**
 * 合成 actor（p0、未来的定时抓取）用这个前缀落审计列，verify 一律直接拒——
 * 「是谁干的」要能写进库，但这行记录不许变成一条能登录的后门。
 */
export const UNUSABLE_PREFIX = "!";

const SCHEME = "scrypt";
const SALT_BYTES = 16;
const KEY_BYTES = 64;

/**
 * 存储形状 `scrypt$<salt-hex>$<hash-hex>`：scheme 段留着，将来换算法时旧行还判得出是谁写的。
 *
 * 为什么是 stdlib 的 scrypt 而不是 argon2/bcrypt：两者都要原生编译或 prebuilt 二进制，
 * 而本案是单人自部署、口令校验频率以登录计。少一条供应链，也少一类 Windows 装不上的故障。
 * 代价是抗 GPU 破解弱于 argon2，将来接公网自助注册时要换——那时是加一个 scheme，不是重写。
 *
 * scryptSync 会占住事件循环约 50–100ms：登录速率下无所谓，但它不该出现在按请求跑的路径里。
 */
export function hashPassword(password: string): string {
  const salt = randomBytes(SALT_BYTES).toString("hex");
  const hash = scryptSync(password, salt, KEY_BYTES).toString("hex");
  return `${SCHEME}$${salt}$${hash}`;
}

/**
 * 存储值形状不对一律返回 false，不抛：password_hash 是个 text 列，
 * 历史值、手填值、别的方案的串都混在同一列里，校验函数不许因为脏数据把进程带下去。
 */
export function verifyPassword(password: string, stored: string): boolean {
  if (stored.startsWith(UNUSABLE_PREFIX)) return false;
  const [scheme, salt, hash] = stored.split("$");
  if (scheme !== SCHEME || !salt || !hash) return false;
  const actual = scryptSync(password, salt, KEY_BYTES);
  const expected = Buffer.from(hash, "hex");
  // timingSafeEqual 要求等长，长度本身就是「这不是我们写出去的哈希」的信号，先挡掉再比
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}
