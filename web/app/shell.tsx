// The app's narrow phone column with the prototype line under it. The parent, child and login screens sit in it;
// the landing page at / is full width and has its own footer.
export function AppMain({ children }: { children: React.ReactNode }) {
  return (
    <main>
      {children}
      <p className="fine">Prototype. Demo money only, no real payments.</p>
    </main>
  );
}
