"use client";

import { useRouter } from "next/navigation";
import { setupApi } from "@/lib/setup";
import { BudgetsForm } from "../../../../plan-forms";
import { Back, Loading, Masthead } from "../../../../ui";
import { useSession } from "../../../../use-session";

// Change the budgets and pocket money after setup: the same boxes as the setup step, filled with what the family
// has now, and the app's starting point as a guide under each. Saving replaces them (POST /api/setup/budgets).
export default function ChangeBudgetsPage() {
  const { me } = useSession("PARENT");
  const router = useRouter();
  if (!me) return <Loading />;
  return (
    <div className="stack">
      <Back href="/parent/money/budgets" label="Budgets" />
      <Masthead title="Change budgets" sub="Each month, and the kids' pocket money each week" />
      <BudgetsForm load={setupApi.currentBudgets} saveLabel="Save budgets"
        onSaved={() => router.push("/parent/money/budgets")}
        lede="Change any amount and save. The bars, alerts and money buckets use the new amounts straight away." />
    </div>
  );
}
