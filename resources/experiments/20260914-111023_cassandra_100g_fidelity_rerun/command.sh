#!/usr/bin/env bash
cd /home/dongju/vcomp
env RUN_STAMP=20260914-111023 DATASET_GIB=100 CAMPAIGN_ROOT=/work/vcomp-pebble-1tb/cassandra-fidelity-100g-20260914-111023 RESULT_ROOT=/home/dongju/vcomp/resources/experiments/20260914-111023_cassandra_100g_fidelity_rerun COMPACTION_DRAIN_TIMEOUT_SECONDS=86400 bash /home/dongju/vcomp/experiments/scripts/cassandra/run_fidelity_100g_campaign.sh
# Use NEW paths and RUN_STAMP for a repeat. Existing paths are deliberately refused.
