package com.wenjin.eval.fixture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Recording mistake port for unit tests. */
public final class InMemoryEvalMistakePort implements EvalMistakePort {

    public record Seed(long studentId, long courseId, long nodeId, String misconceptionNodeCode, int minutesAgo) {}

    private final List<Seed> seeds = new CopyOnWriteArrayList<>();

    @Override
    public boolean recordMistake(long studentId, long courseId, long nodeId, String misconceptionNodeCode,
                                 int minutesAgo) {
        seeds.add(new Seed(studentId, courseId, nodeId, misconceptionNodeCode, minutesAgo));
        return true;
    }

    public List<Seed> seeds() {
        return List.copyOf(seeds);
    }
}
