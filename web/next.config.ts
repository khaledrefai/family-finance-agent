import type { NextConfig } from "next";

// The browser calls /api/... on the web app itself and Next.js forwards it to the Spring Boot core,
// and /agent/... to the Python agent service. That way there is no CORS setup and neither is exposed directly.
const coreUrl = process.env.CORE_URL ?? "http://localhost:8080";
const agentsUrl = process.env.AGENTS_URL ?? "http://localhost:8000";

const nextConfig: NextConfig = {
  output: "standalone",
  // Reading a receipt photo can take the AI up to 40 seconds; the forwarding would give up at 30 by default
  experimental: { proxyTimeout: 60_000 },
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${coreUrl}/api/:path*` },
      { source: "/agent/:path*", destination: `${agentsUrl}/:path*` },
    ];
  },
};

export default nextConfig;
