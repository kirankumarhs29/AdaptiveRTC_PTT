#!/bin/bash
set -e
cd /mnt/d/dev_env/combineBoth/AdaptiveRTC/build_linux

TESTS="test_packet test_rtt_tracker test_ecs_detector test_jitter_buffer test_rate_controller test_network_simulator test_adaptive_jitter_buffer integration_test"

PASS=0
FAIL=0

for t in $TESTS; do
    echo "=== Running $t ==="
    if ./bin/$t; then
        echo "PASS: $t"
        PASS=$((PASS+1))
    else
        echo "FAIL: $t (exit code $?)"
        FAIL=$((FAIL+1))
    fi
    echo ""
done

echo "==============================="
echo "Results: $PASS passed, $FAIL failed"
echo "==============================="
