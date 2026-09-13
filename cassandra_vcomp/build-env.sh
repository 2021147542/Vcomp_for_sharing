#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

# Reproducible local build environment for Cassandra 5.0.9.
# Source this file before invoking Ant:
#   source ./build-env.sh

VCOMP_BUILD_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export ANT_HOME="$VCOMP_BUILD_ROOT/.tools/ant-root/usr/share/ant"
export PATH="$ANT_HOME/bin:$JAVA_HOME/bin:$PATH"
export ANT_ARGS="${ANT_ARGS:-} -Dlocal.repository=$VCOMP_BUILD_ROOT/.m2/repository"
