"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, Masthead } from "../../ui";

// Header and the five sections of the Money tab. Bills and Uploads live on the overview for now.
const SECTIONS = [
  { href: "/parent/money", label: "Overview" },
  { href: "/parent/money#bills", label: "Bills" },
  { href: "/parent/money/budgets", label: "Budgets" },
  { href: "/parent/money#uploads", label: "Uploads" },
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
          <Link href="/parent/money/add" className="askbar" style={{ background: "var(--ink)", color: "var(--on-ink)", borderColor: "var(--ink)", flex: "none", fontWeight: 500, fontSize: 14 }}>
            <Icon name="plus" size={16} />
            Add spending
          </Link>
        }
      />
      <nav className="seg" aria-label="Money sections" style={{ marginTop: -12 }}>
        {SECTIONS.map((s) => (
          <Link key={s.label} href={s.href} aria-current={s.href === path ? "page" : undefined}>
            {s.label}
          </Link>
        ))}
      </nav>
    </>
  );
}
