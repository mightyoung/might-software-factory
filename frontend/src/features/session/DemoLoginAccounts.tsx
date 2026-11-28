import { DEMO_ACCOUNTS } from "../../adapters/demoAccounts";

export function DemoLoginAccounts({ pending, onSelect }: {
  pending: boolean;
  onSelect: (name: string) => void;
}) {
  return (
    <aside className="login-demo" aria-label="合成演示说明">
      <p>
        <strong>合成演示：</strong>
        账号只认下列演示账号，密码任意非空即可。这不是真实认证，不提供任何安全保证，刷新页面后需重新登录。
      </p>
      <ul>
        {Object.entries(DEMO_ACCOUNTS).map(([name, account]) => (
          <li key={name}>
            <button
              type="button"
              className="login-demo-fill"
              onClick={() => onSelect(name)}
              disabled={pending}
            >
              {name}
            </button>
            <span>{account.description}</span>
          </li>
        ))}
      </ul>
    </aside>
  );
}
