import { LiveToast } from "../live-ui";
import { AppMain } from "../shell";
import { TabBar } from "../ui";

// Every child screen shares the bottom tab bar, and the toast that shows live news. The kid-app wrapper switches on the kids' look.
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <AppMain>
      <div className="kid-app">
        <LiveToast />
        {children}
        <TabBar who="child" />
      </div>
    </AppMain>
  );
}
