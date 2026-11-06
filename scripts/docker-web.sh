#!/bin/bash

# Web Frontend Docker Build & Test Script
# Gavel Auction Project
# 
# Usage: ./scripts/docker-web.sh <command> [options]
# 
# Commands:
#   build          Build the web Docker image
#   run            Run the web container
#   dev            Run in development mode with hot reload
#   test           Build and run quick tests
#   logs           View container logs
#   stop           Stop containers
#   clean          Remove containers and images
#   help           Show this help message

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
IMAGE_NAME="${IMAGE_NAME:-gavel-web}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
CONTAINER_NAME="${CONTAINER_NAME:-gavel-web}"
PORT_RUNTIME="${PORT_RUNTIME:-8787}"
PORT_DEV="${PORT_DEV:-3000}"
REGISTRY="${REGISTRY:-}"

# Functions
print_header() {
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

print_usage() {
    cat << EOF
${BLUE}Gavel Auction Web Docker Script${NC}

Usage: $0 <command> [options]

${BLUE}Commands:${NC}

  build [--no-cache]
    Build the web Docker image
    Options:
      --no-cache    Skip Docker cache (full rebuild)
      --dev         Build development image
    Example:
      $0 build
      $0 build --no-cache --dev

  run [--ports]
    Run the web container in production mode
    Options:
      --ports 8080:8787  Map different ports
    Example:
      $0 run
      $0 run --ports 9000:8787

  dev
    Run in development mode with hot reload
    - Mounts source code for live changes
    - Enables npm run dev
    - Opens ports 3000 and 8787
    Example:
      $0 dev

  test
    Build and run quick tests
    - Verifies image builds successfully
    - Runs health check
    - Tests basic endpoints
    Example:
      $0 test

  logs [--follow] [--lines N]
    View container logs
    Options:
      --follow      Follow logs (like tail -f)
      --lines 100   Show last N lines
    Example:
      $0 logs
      $0 logs --follow --lines 50

  stop
    Stop running containers
    Example:
      $0 stop

  clean [--all]
    Remove containers and images
    Options:
      --all    Also remove images and volumes
    Example:
      $0 clean
      $0 clean --all

  push [--registry REGISTRY]
    Push image to registry
    Options:
      --registry    Registry URL (default: \$REGISTRY env var)
    Example:
      $0 push --registry docker.io/username

  help
    Show this help message

${BLUE}Environment Variables:${NC}

  IMAGE_NAME      Docker image name (default: gavel-web)
  IMAGE_TAG       Docker image tag (default: latest)
  CONTAINER_NAME  Container name (default: gavel-web)
  PORT_RUNTIME    Runtime port (default: 8787)
  PORT_DEV        Dev server port (default: 3000)
  REGISTRY        Docker registry URL

${BLUE}Examples:${NC}

  # Build and run in production
  $0 build
  $0 run

  # Build and run in development with hot reload
  $0 build --dev
  $0 dev

  # Run tests
  $0 test

  # View live logs
  $0 logs --follow

  # Clean up everything
  $0 clean --all

  # Push to registry
  REGISTRY=docker.io/username $0 push

EOF
}

cmd_build() {
    local no_cache=""
    local build_env="production"
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --no-cache)
                no_cache="--no-cache"
                shift
                ;;
            --dev)
                build_env="development"
                shift
                ;;
            *)
                print_error "Unknown option: $1"
                return 1
                ;;
        esac
    done
    
    print_header "Building Web Docker Image"
    print_info "Image: $IMAGE_NAME:$IMAGE_TAG"
    print_info "Mode: $build_env"
    
    docker build \
        $no_cache \
        --build-arg NODE_VERSION=22.13.0 \
        --build-arg BUILD_ENV=$build_env \
        -t "$IMAGE_NAME:$IMAGE_TAG" \
        -f web/Dockerfile \
        .
    
    if [ $? -eq 0 ]; then
        print_success "Build complete"
        docker images | grep "$IMAGE_NAME" | head -1
    else
        print_error "Build failed"
        return 1
    fi
}

cmd_run() {
    local ports="$PORT_RUNTIME:8787"
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --ports)
                ports="$2"
                shift 2
                ;;
            *)
                print_error "Unknown option: $1"
                return 1
                ;;
        esac
    done
    
    print_header "Running Web Container (Production Mode)"
    print_info "Image: $IMAGE_NAME:$IMAGE_TAG"
    print_info "Port: $ports"
    print_info "Container: $CONTAINER_NAME"
    
    # Stop existing container if running
    docker stop $CONTAINER_NAME 2>/dev/null || true
    docker rm $CONTAINER_NAME 2>/dev/null || true
    
    docker run -d \
        --name $CONTAINER_NAME \
        -p "$ports" \
        -e NODE_ENV=production \
        "$IMAGE_NAME:$IMAGE_TAG"
    
    if [ $? -eq 0 ]; then
        print_success "Container started"
        sleep 2
        cmd_health_check
    else
        print_error "Failed to start container"
        return 1
    fi
}

cmd_dev() {
    print_header "Running Web Container (Development Mode)"
    print_info "Image: $IMAGE_NAME:$IMAGE_TAG"
    print_info "Ports: $PORT_DEV (dev) / $PORT_RUNTIME (runtime)"
    print_info "Mode: Hot reload enabled"
    
    # Stop existing container if running
    docker-compose down -v 2>/dev/null || true
    
    docker-compose -f docker-compose.yml -f docker-compose.dev.yml up web
}

cmd_test() {
    print_header "Testing Web Docker Setup"
    
    # Build
    print_info "Step 1/3: Building image..."
    cmd_build || return 1
    
    # Run
    print_info "Step 2/3: Starting container..."
    cmd_run || return 1
    
    # Health check
    print_info "Step 3/3: Testing health..."
    sleep 3
    cmd_health_check || return 1
    
    print_success "All tests passed!"
}

cmd_health_check() {
    local max_attempts=10
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        if docker exec $CONTAINER_NAME curl -s http://localhost:8787/ > /dev/null 2>&1; then
            print_success "Health check passed"
            return 0
        fi
        
        print_info "Health check attempt $attempt/$max_attempts..."
        sleep 2
        attempt=$((attempt + 1))
    done
    
    print_error "Health check failed after $max_attempts attempts"
    return 1
}

cmd_logs() {
    local follow=""
    local lines=""
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --follow)
                follow="-f"
                shift
                ;;
            --lines)
                lines="--tail $2"
                shift 2
                ;;
            *)
                print_error "Unknown option: $1"
                return 1
                ;;
        esac
    done
    
    print_header "Container Logs: $CONTAINER_NAME"
    docker logs $follow $lines $CONTAINER_NAME
}

cmd_stop() {
    print_header "Stopping Containers"
    
    if docker stop $CONTAINER_NAME 2>/dev/null; then
        print_success "Container stopped"
    else
        print_info "Container not running"
    fi
    
    docker-compose down 2>/dev/null || true
    print_success "All containers stopped"
}

cmd_clean() {
    local remove_all=""
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --all)
                remove_all=true
                shift
                ;;
            *)
                print_error "Unknown option: $1"
                return 1
                ;;
        esac
    done
    
    print_header "Cleaning Docker Resources"
    
    # Stop containers
    print_info "Stopping containers..."
    docker-compose down -v 2>/dev/null || true
    docker stop $CONTAINER_NAME 2>/dev/null || true
    
    # Remove containers
    print_info "Removing containers..."
    docker rm $CONTAINER_NAME 2>/dev/null || true
    docker-compose rm -f 2>/dev/null || true
    
    if [ "$remove_all" = true ]; then
        print_info "Removing images..."
        docker rmi "$IMAGE_NAME:$IMAGE_TAG" 2>/dev/null || true
        
        print_info "Removing volumes..."
        docker volume prune -f 2>/dev/null || true
        
        print_success "All resources cleaned"
    else
        print_success "Containers cleaned (use --all to remove images)"
    fi
}

cmd_push() {
    local registry="$REGISTRY"
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            --registry)
                registry="$2"
                shift 2
                ;;
            *)
                print_error "Unknown option: $1"
                return 1
                ;;
        esac
    done
    
    if [ -z "$registry" ]; then
        print_error "Registry not specified. Use --registry or set REGISTRY env var"
        return 1
    fi
    
    print_header "Pushing Image to Registry"
    print_info "Registry: $registry"
    print_info "Image: $IMAGE_NAME:$IMAGE_TAG"
    
    # Tag image
    docker tag "$IMAGE_NAME:$IMAGE_TAG" "$registry/$IMAGE_NAME:$IMAGE_TAG"
    
    # Push
    docker push "$registry/$IMAGE_NAME:$IMAGE_TAG"
    
    if [ $? -eq 0 ]; then
        print_success "Image pushed successfully"
    else
        print_error "Push failed"
        return 1
    fi
}

# Main
main() {
    if [ $# -eq 0 ]; then
        print_usage
        return 1
    fi
    
    local command=$1
    shift
    
    case $command in
        build)
            cmd_build "$@"
            ;;
        run)
            cmd_run "$@"
            ;;
        dev)
            cmd_dev "$@"
            ;;
        test)
            cmd_test "$@"
            ;;
        logs)
            cmd_logs "$@"
            ;;
        stop)
            cmd_stop "$@"
            ;;
        clean)
            cmd_clean "$@"
            ;;
        push)
            cmd_push "$@"
            ;;
        help|--help|-h)
            print_usage
            ;;
        *)
            print_error "Unknown command: $command"
            print_usage
            return 1
            ;;
    esac
}

# Run main
main "$@"
