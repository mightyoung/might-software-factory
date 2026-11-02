/** 改密请求的限制，来自 contracts/openapi/session-api.yaml 的 PasswordChangeRequest。密码不去空白、不做规范化。 */
export const PASSWORD_LIMITS = {
  maxUtf8Bytes: 72,
  newMinCodePoints: 15,
} as const;

const encoder = new TextEncoder();

export function utf8ByteLength(value: string): number {
  return encoder.encode(value).length;
}

function codePointCount(value: string): number {
  let count = 0;
  for (const _ of value) count += 1;
  return count;
}

export interface PasswordChangeInput {
  current: string;
  next: string;
  confirm: string;
}
export type PasswordChangeErrors = Partial<Record<keyof PasswordChangeInput, string>>;

export function validatePasswordChange({ current, next, confirm }: PasswordChangeInput): PasswordChangeErrors {
  const errors: PasswordChangeErrors = {};
  if (current.length === 0) errors.current = "请输入当前密码";
  else if (utf8ByteLength(current) > PASSWORD_LIMITS.maxUtf8Bytes) errors.current = "当前密码不正确";

  if (codePointCount(next) < PASSWORD_LIMITS.newMinCodePoints) {
    errors.next = `新密码至少 ${PASSWORD_LIMITS.newMinCodePoints} 个字符`;
  } else if (utf8ByteLength(next) > PASSWORD_LIMITS.maxUtf8Bytes) {
    errors.next = `新密码不能超过 ${PASSWORD_LIMITS.maxUtf8Bytes} 字节（一个汉字占 3 字节，约 24 个汉字）`;
  }

  if (!errors.next && confirm !== next) errors.confirm = "两次输入的新密码不一致";
  return errors;
}
