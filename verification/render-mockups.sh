#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source /home/ubuntu/toolchain/env.sh
java -Djava.awt.headless=true verification/RenderMockups.java "${1:-/home/ubuntu/scratch/phase23-logs/previews}"
