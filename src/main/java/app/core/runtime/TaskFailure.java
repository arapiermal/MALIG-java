package app.core.runtime;

/** Expected work failure (unsupported input, rejected proposal or reasoning limit), not a broken agent. */
public class TaskFailure extends IllegalStateException {
    public TaskFailure(String detail) { super(detail); }
}
