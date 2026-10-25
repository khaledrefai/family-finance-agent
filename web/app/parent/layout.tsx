import { LiveToast } from "../live-ui";
import { AppMain } from "../shell";
import { TabBar } from "../ui";

// Every parent screen shares the bottom tab bar, and the toast that shows live news.
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <AppMain>
      <LiveToast />
      {children}
      <TabBar who="parent" />
    </AppMain>
  );
}
