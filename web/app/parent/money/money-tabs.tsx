"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, Masthead } from "../../ui";

// Header and the three sections of the Money tab. Bills and uploads are sections of the overview.
const SECTIONS = [
  { href: "/parent/money", label: "Overview" },
  { href: "/parent/money/budgets", label: "Budgets" },
  { href: "/parent/money/accounts", label: "Accounts" },
];

export function MoneyHeader({ sub }: { sub: string }) {
  const path = usePathname();
  return (
    <>
      <Masthead
        title="Money"
        sub={sub}
        action={
          <Link href="/parent/money/add" className="btn" style={{ flex: "none", minHeight: 44, padding: "0 14px", fontSize: 14 }}>
            <Icon name="plus" size={16} />
            Add spending
          </Link>
        }
      />
      <nav className="seg" aria-label="Money sections">
        {SECTIONS.map((s) => (
          <Link key={s.label} href={s.href} aria-current={s.href === path ? "page" : undefined}>
            {s.label}
          </Link>
        ))}
      </nav>
    </>
  );
}
