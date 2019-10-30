#!/bin/bash

# Run the test which loads the Okra lib from a jar
echo "--- Running com.oracle.graal.compiler.hsail.test.BasicSumatraTest ---"
mx vm -Dcom.amd.sumatra.offload.immediate=true \
      -Xbootclasspath/p:graal.jar \
      -XX:-BootstrapGraal \
      -XX:+UseHSAILSimulator \
      -XX:+TraceGPUInteraction \
      -cp @com.oracle.graal.compiler.hsail.test \
      com.oracle.graal.compiler.hsail.test.BasicSumatraTest

# Run the sample which requires PATH and LD_LIBRARY_PATH for loading Okra
if [ -z "$OKRA_BIN" ]; then
    OKRA_BIN=$HOME/okra/dist/bin
fi
test -x $OKRA_BIN/hsailasm || { echo "$OKRA_BIN is missing hsailasm" ; exit 1; }

export PATH=$PATH:$OKRA_BIN
export LD_LIBRARY_PATH=$OKRA_BIN

echo "--- Running com.oracle.graal.hotspot.hsail.BasicSumatraDemo ---"
mx vm -Dcom.amd.sumatra.offload.immediate=true \
      -Xbootclasspath/p:graal.jar \
      -XX:-BootstrapGraal \
      -XX:+UseHSAILSimulator \
      -XX:+TraceGPUInteraction \
      -cp @com.oracle.graal.hotspot.hsail \
      com.oracle.graal.hotspot.hsail.BasicSumatraDemo
