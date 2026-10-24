import { TabBar } from "../ui";

// Every child screen shares the bottom tab bar. The kid-app wrapper switches on the kids' look.
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <div className="kid-app">
      {children}
      <TabBar who="child" />
    </div>
  );
}
