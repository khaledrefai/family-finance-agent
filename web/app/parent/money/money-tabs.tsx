"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { Icon, Masthead } from "../../ui";

// Header and the five sections of the Money tab. Bills and Uploads live on the overview for now,
// so they are anchors there, and the bar reads the #hash to show which one you are on.
const SECTIONS = [
  { href: "/parent/money", label: "Overview" },
  { href: "/parent/money#bills", label: "Bills" },
  { href: "/parent/money/budgets", label: "Budgets" },
  { href: "/parent/money#uploads", label: "Uploads" },
  { href: "/parent/money/accounts", label: "Accounts" },
];

export function MoneyHeader({ sub }: { sub: string }) {
  const path = usePathname();
  const [hash, setHash] = useState("");
  // Next's Link changes the hash with pushState, which fires no hashchange, so clicks set it too
  useEffect(() => {
    const read = () => setHash(window.location.hash);
    read();
    window.addEventListener("hashchange", read);
    return () => window.removeEventListener("hashchange", read);
  }, [path]);
  const here = `${path}${hash}`;
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
          <Link key={s.label} href={s.href} aria-current={s.href === here ? "page" : undefined}
            onClick={() => setHash(s.href.includes("#") ? s.href.slice(s.href.indexOf("#")) : "")}>
            {s.label}
          </Link>
        ))}
      </nav>
    </>
  );
}
