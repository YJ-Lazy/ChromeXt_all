package de.robv.android.xposed.callbacks;
public abstract class XCallback {
    public static final int PRIORITY_DEFAULT = 50;
    public final int priority;
    protected XCallback(int priority) { this.priority = priority; }
}
