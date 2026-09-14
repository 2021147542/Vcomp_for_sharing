# Cassandra 100 GiB fidelity rerun — running

Follow [status.env](status.env) for the current phase and terminal status. The completed canonical loads are reused; all seven workload pairs restart from fresh checkpoints.

Load and workload-reader binaries have separate provenance in load-reuse-provenance.json.

Raw logs and retained databases: `/work/vcomp-pebble-1tb/cassandra-organized-100g-20260914-152956`.

Configuration: 24 B key + 1000 B value; 64 MiB flush/SST; 10000 partitions; calibrated size model; seed 20260909; 48 workers, 300 seconds per A–F/MixGraph cell (time mode).

Results will be published here automatically. Completion and fidelity are separate: the target is symmetric ±10% similarity, excluding loading time and loading write amplification. See [campaign.env](campaign.env) and [command.sh](command.sh) for reproducibility.
