import { TabBar } from "../ui";

// Every parent screen shares the bottom tab bar.
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <>
      {children}
      <TabBar who="parent" />
    </>
  );
}
