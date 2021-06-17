#! /bin/sh
# Author: Arne Köhn <arne@chark.eu>
# License: Apache 2.0

set -e
git clone git@github.com:PaperMC/Paper.git
cd Paper
git checkout dev/1.17
./gradlew applyPatches
./gradlew runShadow

# now you can copy Paper-Server/build/libs/Paper.*-mojang-mapped.jar to wherever you want.
# cp Paper-Server/build/libs/Paper.*-mojang-mapped.jar ........
