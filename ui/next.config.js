/** @type {import('next').NextConfig} */

// Override with BACKEND_URL if the Java server runs on a non-default port,
// e.g. `BACKEND_URL=http://localhost:8080 npm run dev`.
// NOTE: for production (`next build` + `next start`) the rewrite targets are
// baked at BUILD time — set BACKEND_URL when running `npm run build`.
const agentUrl = process.env.FYLO_AGENT_URL ?? 'http://127.0.0.1:7000';
const backendUrl = process.env.BACKEND_URL ?? agentUrl;

const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  async rewrites() {
    return [
      {
        source: '/api/upload',
        destination: `${agentUrl}/upload`,
      },
      {
        source: '/api/download/:port',
        destination: `${agentUrl}/download/:port`,
      },
      {
        source: '/api/agent/:path*',
        destination: `${agentUrl}/agent/:path*`,
      },
      {
        source: '/api/transfers',
        destination: `${agentUrl}/agent/transfers`,
      },
      {
        source: '/api/transfers/:path*',
        destination: `${agentUrl}/agent/transfers/:path*`,
      },
      // Unified platform API (auth, users, requests, links, plan)
      {
        source: '/api/v1/:path*',
        destination: `${backendUrl}/api/v1/:path*`,
      },
      // Public link downloads
      {
        source: '/s/:slug',
        destination: `${backendUrl}/s/:slug`,
      },
    ];
  },
}

module.exports = nextConfig
