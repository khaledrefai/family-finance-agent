import { LiveToast } from "../live-ui";
import { SetupGuard } from "../setup-guard";
import { AppMain } from "../shell";
import { TabBar } from "../ui";

// Every parent screen shares the bottom tab bar, and the toast that shows live news.
// A family still setting up is sent to /setup first.
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <AppMain>
      <SetupGuard />
      <LiveToast />
      {children}
      <TabBar who="parent" />
    </AppMain>
  );
}
