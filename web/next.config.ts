import type { NextConfig } from "next";

// The browser calls /api/... on the web app itself and Next.js forwards it to the Spring Boot core,
// and /agent/... to the Python agent service. That way there is no CORS setup and neither is exposed directly.
const coreUrl = process.env.CORE_URL ?? "http://localhost:8080";
const agentsUrl = process.env.AGENTS_URL ?? "http://localhost:8000";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [
      { source: "/api/:path*", destination: `${coreUrl}/api/:path*` },
      { source: "/agent/:path*", destination: `${agentsUrl}/:path*` },
    ];
  },
};

export default nextConfig;
