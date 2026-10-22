package com.wenjin.eval.fixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recording roster port for unit tests (no database). */
public final class InMemoryEvalRosterPort implements EvalRosterPort {

    public record Mastery(long studentId, long nodeId, double raw01) {}

    public record Snapshot(long studentId, long nodeId, double score01, int daysAgo) {}

    public record Answer(long studentId, long nodeId, boolean correct, int minutesAgo) {}

    private final Map<Integer, String> namesBySlot = new LinkedHashMap<>();
    private final List<Long> enrolled = new ArrayList<>();
    private final List<Long> otherCourseStudents = new ArrayList<>();
    private final List<Mastery> masteries = new ArrayList<>();
    private final List<Snapshot> snapshots = new ArrayList<>();
    private final List<Answer> answers = new ArrayList<>();
    private final Map<Long, String> privateChats = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private boolean primaryEnrolled = true;

    @Override
    public void clearRoster() {
        calls.add("clearRoster");
        enrolled.clear();
        otherCourseStudents.clear();
        masteries.clear();
        snapshots.clear();
        answers.clear();
        privateChats.clear();
    }

    @Override
    public void setPrimaryStudentEnrolled(long studentId, long courseId, boolean enrolled) {
        calls.add("primaryEnrolled=" + enrolled);
        primaryEnrolled = enrolled;
    }

    @Override
    public long ensureRosterUser(int slot, String realName) {
        namesBySlot.put(slot, realName);
        return 1000L + slot;
    }

    @Override
    public void enroll(long studentId, long courseId) {
        enrolled.add(studentId);
    }

    @Override
    public long enrollInOtherCourse(long studentId, long teacherId) {
        otherCourseStudents.add(studentId);
        return 999L;
    }

    @Override
    public void writeMastery(long studentId, long courseId, long nodeId, double raw01) {
        masteries.add(new Mastery(studentId, nodeId, raw01));
    }

    @Override
    public void writeSnapshot(long studentId, long courseId, long nodeId, double score01, int daysAgo) {
        snapshots.add(new Snapshot(studentId, nodeId, score01, daysAgo));
    }

    @Override
    public boolean recordAnswer(long studentId, long courseId, long nodeId, boolean correct, int minutesAgo) {
        answers.add(new Answer(studentId, nodeId, correct, minutesAgo));
        return true;
    }

    @Override
    public void recordPrivateChat(long studentId, long courseId, String text) {
        privateChats.put(studentId, text);
    }

    @Override
    public void clearTeacherState(long teacherId, long courseId) {
        calls.add("clearTeacherState");
    }

    public Map<Integer, String> namesBySlot() {
        return Map.copyOf(namesBySlot);
    }

    public List<Long> enrolled() {
        return List.copyOf(enrolled);
    }

    public List<Long> otherCourseStudents() {
        return List.copyOf(otherCourseStudents);
    }

    public List<Mastery> masteries() {
        return List.copyOf(masteries);
    }

    public List<Snapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    public List<Answer> answers() {
        return List.copyOf(answers);
    }

    public Map<Long, String> privateChats() {
        return Map.copyOf(privateChats);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public boolean primaryEnrolled() {
        return primaryEnrolled;
    }
}
