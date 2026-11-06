# Multi-stage Dockerfile for Gavel Auction Web Frontend
# 
# This Dockerfile builds a Next.js frontend using Vinext for optimal performance.
# The frontend is compiled to static assets and optimized for deployment to 
# Cloudflare Workers or traditional Node.js servers.
#
# Build Args:
#   NODE_VERSION: Node.js version (default: 22.13.0, must match engines.node in package.json)
#   BUILD_ENV: Environment for build ('production' or 'development', default: 'production')

# ============================================================================
# Stage 1: Builder - Install dependencies and build application
# ============================================================================
FROM node:22.13.0-alpine AS builder

WORKDIR /app

# Install build dependencies
RUN apk add --no-cache \
    git \
    python3 \
    make \
    g++ \
    ca-certificates

# Copy package files
COPY web/package*.json ./

# Install dependencies with npm ci for reproducible builds
# npm ci is preferred over npm install in CI/CD environments
RUN npm ci --prefer-offline --no-audit

# Copy application source code
COPY web/ .

# Build the application
# Vinext compiles the Next.js app to optimized output in ./dist
RUN npm run build

# ============================================================================
# Stage 2: Runtime - Serve the application
# ============================================================================
FROM node:22.13.0-alpine

# Security: Create non-root user
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# Copy only necessary files from builder stage
COPY --from=builder --chown=app:app /app/dist ./dist
COPY --from=builder --chown=app:app /app/node_modules ./node_modules
COPY --from=builder --chown=app:app /app/package*.json ./

# Install only production dependencies for runtime
# Remove dev dependencies to reduce image size
RUN npm ci --production --prefer-offline --no-audit && \
    npm cache clean --force && \
    rm -rf .npm

# Switch to non-root user
USER app

# Expose port (Vinext/Wrangler typically runs on 8787)
EXPOSE 8787

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD node -e "require('http').get('http://localhost:8787', (res) => { if (res.statusCode !== 200 && res.statusCode !== 404) throw new Error(res.statusCode); })" || exit 1

# Start application
# For Wrangler/Cloudflare Workers development
CMD ["npm", "start"]

# ============================================================================
# Alternative Build Targets (Use with: docker build --target <target>)
# ============================================================================
# 
# For static export (if needed):
#   docker build --target static -t gavel-web:static .
#
# For development:
#   docker run -it -p 3000:3000 -v $(pwd)/web:/app gavel-web npm run dev
