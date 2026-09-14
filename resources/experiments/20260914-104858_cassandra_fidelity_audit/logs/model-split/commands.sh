#!/usr/bin/env bash
# Reproduction from /home/dongju/vcomp; prior implementation preserved in before/.
set -euo pipefail
cd /home/dongju/vcomp
vcomp_audit_root=/tmp/vcomp-model-audit
vcomp_java=/usr/lib/jvm/java-11-openjdk-amd64/bin/java
vcomp_javac=/usr/lib/jvm/java-11-openjdk-amd64/bin/javac
vcomp_base_cp='cassandra_vcomp/build/classes/main:cassandra_vcomp/build/lib/jars/*:cassandra_vcomp/build/test/lib/jars/*:cassandra_vcomp/conf'
"$vcomp_javac" -cp "$vcomp_base_cp" -d "$vcomp_audit_root/classes" cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/VCompLearnedModel.java cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/DefaultVirtualCompaction.java cassandra_vcomp/test/unit/org/apache/cassandra/db/compaction/vcomp/DefaultVirtualCompactionTest.java
"$vcomp_javac" -cp "$vcomp_base_cp" -d "$vcomp_audit_root/probe" "$vcomp_audit_root/ModelSplitProbe.java"
"$vcomp_java" -cp "$vcomp_audit_root/classes:$vcomp_base_cp" org.junit.runner.JUnitCore org.apache.cassandra.db.compaction.vcomp.DefaultVirtualCompactionTest
"$vcomp_java" -Dcassandra.ucs.picker_seed=20260909 -cp "$vcomp_audit_root/before:$vcomp_audit_root/probe:$vcomp_base_cp" org.apache.cassandra.db.compaction.vcomp.ModelSplitProbe "$vcomp_audit_root/reproduction-before-1g"
"$vcomp_java" -Dcassandra.ucs.picker_seed=20260909 -cp "$vcomp_audit_root/classes:$vcomp_audit_root/probe:$vcomp_base_cp" org.apache.cassandra.db.compaction.vcomp.ModelSplitProbe "$vcomp_audit_root/reproduction-after-1g"
