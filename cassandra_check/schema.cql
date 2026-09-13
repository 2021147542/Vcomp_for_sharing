CREATE KEYSPACE IF NOT EXISTS vcomp_smoke
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS vcomp_smoke.kv (
    partition_id text,
    ck bigint,
    value blob,
    PRIMARY KEY ((partition_id), ck)
) WITH CLUSTERING ORDER BY (ck ASC)
  AND compaction = {
      'class': 'LeveledCompactionStrategy',
      'enabled': 'false',
      'sstable_size_in_mb': '1'
  }
  AND compression = {'enabled': 'false'};

CREATE TABLE IF NOT EXISTS vcomp_smoke.reconciliation_probe (
    partition_id text,
    ck bigint,
    value blob,
    PRIMARY KEY ((partition_id), ck)
) WITH CLUSTERING ORDER BY (ck ASC);
