package app.core.environment;

/** Observation-only view supplied to reasoning components. */
public interface EnvironmentView {
    WorldStateSnapshot snapshot();
}
