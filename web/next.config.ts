import type { NextConfig } from "next";

// The browser calls /api/... on the web app itself and Next.js forwards it to the Spring Boot core.
// That way there is no CORS setup and the core is not exposed directly.
const coreUrl = process.env.CORE_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${coreUrl}/api/:path*` }];
  },
};

export default nextConfig;
