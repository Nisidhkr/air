/** @type {import('next').NextConfig} */

// Override with BACKEND_URL if the Java server runs on a non-default port,
// e.g. `BACKEND_URL=http://localhost:8080 npm run dev`.
// NOTE: for production (`next build` + `next start`) the rewrite targets are
// baked at BUILD time — set BACKEND_URL when running `npm run build`.
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:7000';

const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  async rewrites() {
    return [
      {
        source: '/api/upload',
        destination: `${backendUrl}/upload`,
      },
      {
        source: '/api/download/:port',
        destination: `${backendUrl}/download/:port`,
      },
      {
        source: '/api/lan/:path*',
        destination: `${backendUrl}/lan/:path*`,
      },
      {
        source: '/api/transfers',
        destination: `${backendUrl}/transfers`,
      },
      {
        source: '/api/transfers/:path*',
        destination: `${backendUrl}/transfers/:path*`,
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
