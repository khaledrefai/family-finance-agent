import { LiveToast } from "../live-ui";
import { TabBar } from "../ui";

// Every parent screen shares the bottom tab bar, and the toast that shows live news.
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <LiveToast />
      {children}
      <TabBar who="parent" />
    </>
  );
}
