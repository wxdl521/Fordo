package com.wenjin.eval.fixture;

import java.util.Optional;
import java.util.Set;

/** Resolve course knowledge nodes for persona injection. */
public interface EvalNodeLookup {

    Optional<Long> nodeIdByCode(long courseId, String nodeCode);

    Set<String> allNodeCodes(long courseId);
}
